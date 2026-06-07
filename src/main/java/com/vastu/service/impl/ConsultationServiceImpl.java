package com.vastu.service.impl;



import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.vastu.dto.BookingRequest;
import com.vastu.dto.BookingResponse;
import com.vastu.enums.ConsultationType;
import com.vastu.exception.BookingValidationException;
import com.vastu.service.ConsultationService;
import com.vastu.service.queue.NotificationRecord;
import com.vastu.service.queue.TelegramDispatchQueue;
import com.vastu.service.queue.TelegramNotificationStateStore;
import com.vastu.service.queue.TelegramNotificationTask;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Core business-logic service for consultation bookings.
 *
 * No database operations — all state is in-memory via
 * {@link TelegramNotificationStateStore}.
 *
 * Processing flow:
 *  1. Validate the incoming request per consultation type.
 *  2. Save uploaded files to disk.
 *  3. Build the Telegram message text.
 *  4. Register a PENDING notification record in the in-memory state store.
 *  5. Enqueue a lightweight task for the dispatch queue.
 *  6. Return the booking confirmation to the caller.
 *
 * Reliability guarantee (within one JVM lifetime):
 *  If enqueueing fails (queue full) the record remains PENDING in the state
 *  store, and the periodic recovery job will re-enqueue it within 5 minutes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsultationServiceImpl implements ConsultationService {

    private final FileStorageService fileStorageService;
    private final TelegramMessageBuilder messageBuilder;
    private final TelegramNotificationStateStore stateStore;
    private final TelegramDispatchQueue dispatchQueue;

    // ── Booking submission ────────────────────────────────────────

    @Override
    public BookingResponse submitBooking(BookingRequest request, HttpServletRequest httpRequest) {

        // 1. Validate per-type required files
        validateRequest(request);

        ConsultationType type = ConsultationType.valueOf(
                request.getConsultationType().toUpperCase());

        // 2. Persist files to disk
        String degreePhotoPath = null;
        String map2DPath       = null;
        String siteFrontPath   = null;

        try {
            if (type == ConsultationType.ONLINE) {
                degreePhotoPath = fileStorageService.store(request.getDegreePhoto(), "degreePhoto");
                map2DPath       = fileStorageService.store(request.getMap2D(),       "map2D");
            } else {
                siteFrontPath   = fileStorageService.store(request.getSiteFront(),   "siteFront");
            }
        } catch (IllegalArgumentException e) {
            Map<String, String> errors = new HashMap<>();
            errors.put("files", e.getMessage());
            throw new BookingValidationException("File validation failed", errors);
        } catch (Exception e) {
            log.error("[ConsultationService] File storage error", e);
            throw new RuntimeException("Failed to store uploaded files. Please try again.", e);
        }

        // 3. Build a reference ID and message text
        String referenceId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        // Build a minimal booking-like map for the message builder
        // (replaces the JPA entity that no longer exists)
        String messageText = messageBuilder.buildMessage(
                referenceId, type, request,
                degreePhotoPath, map2DPath, siteFrontPath,
                extractClientIp(httpRequest),
                httpRequest.getHeader("User-Agent")
        );

        // 4. Resolve attachment paths
        List<Path> attachments = fileStorageService.resolveAll(
                degreePhotoPath, map2DPath, siteFrontPath);

        // 5. Register PENDING record in the state store
        NotificationRecord record = stateStore.save(messageText, attachments);
        log.info("[ConsultationService] Notification registered id={} ref={} type={}",
                record.getId(), referenceId, type);

        // 6. Enqueue — if queue is full the record stays PENDING for recovery
        TelegramNotificationTask task = TelegramNotificationTask.builder()
                .notificationId(record.getId())
                .build();
        dispatchQueue.enqueue(task);
        log.info("[ConsultationService] Task enqueued for notification id={}", record.getId());

        return BookingResponse.builder()
                .success(true)
                .referenceId(referenceId)
                .message("Your consultation has been booked successfully. " +
                         "Pandit Aman Bhatore will contact you shortly.")
                .submittedAt(OffsetDateTime.now())
                .build();
    }

    // ── Validation ────────────────────────────────────────────────

    private void validateRequest(BookingRequest request) {
        Map<String, String> errors = new HashMap<>();
        String type = request.getConsultationType();
        if (type == null) return;

        if ("online".equalsIgnoreCase(type)) {
            if (request.getDegreePhoto() == null || request.getDegreePhoto().isEmpty())
                errors.put("degreePhoto", "House Degree Photo is required for online consultation");
            if (request.getMap2D() == null || request.getMap2D().isEmpty())
                errors.put("map2D", "2D Map is required for online consultation");
        } else if ("offline".equalsIgnoreCase(type)) {
            if (request.getSiteFront() == null || request.getSiteFront().isEmpty())
                errors.put("siteFront", "Front Site Photo is required for offline consultation");
        }

        if (!errors.isEmpty()) {
            throw new BookingValidationException("Required files are missing", errors);
        }
    }

    // ── Utilities ─────────────────────────────────────────────────

    private String extractClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip))
            return ip.split(",")[0].trim();
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isBlank()) return ip.trim();
        return request.getRemoteAddr();
    }
}
