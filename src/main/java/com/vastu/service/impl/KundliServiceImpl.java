package com.vastu.service.impl;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.vastu.dto.KundliRequest;
import com.vastu.dto.KundliResponse;
import com.vastu.service.KundliService;
import com.vastu.service.queue.NotificationRecord;
import com.vastu.service.queue.TelegramDispatchQueue;
import com.vastu.service.queue.TelegramNotificationStateStore;
import com.vastu.service.queue.TelegramNotificationTask;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Business-logic service for Kundli check submissions.
 *
 * No file uploads — the Kundli form collects birth details only (text/JSON).
 * Uses the same queue + state-store infrastructure as ConsultationServiceImpl.
 *
 * Processing flow:
 *  1. Build a unique reference ID.
 *  2. Format the Telegram message via KundliMessageBuilder.
 *  3. Register a PENDING NotificationRecord in the shared state store.
 *  4. Enqueue a lightweight TelegramNotificationTask.
 *  5. Return KundliResponse to the caller.
 *
 * Reliability guarantee (within one JVM lifetime):
 *  If the queue is full the record remains PENDING in the state store and
 *  NotificationRecoveryService will re-enqueue it within 5 minutes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KundliServiceImpl implements KundliService {

    private final KundliMessageBuilder messageBuilder;
    private final TelegramNotificationStateStore stateStore;
    private final TelegramDispatchQueue dispatchQueue;

    @Override
    public KundliResponse submitKundli(KundliRequest request, HttpServletRequest httpRequest) {

        // 1. Generate short reference ID (8 chars, uppercase)
        String referenceId = UUID.randomUUID().toString()
                .substring(0, 8)
                .toUpperCase();

        // 2. Build Telegram message text
        String messageText = messageBuilder.buildMessage(
                referenceId,
                request,
                extractClientIp(httpRequest),
                httpRequest.getHeader("User-Agent")
        );

        // 3. Register a PENDING notification record (no file attachments)
        NotificationRecord record = stateStore.save(messageText, java.util.Collections.emptyList());
        log.info("[KundliService] Notification registered id={} ref={} name={}",
                record.getId(), referenceId, request.getFullName());

        // 4. Enqueue for async Telegram delivery
        TelegramNotificationTask task = TelegramNotificationTask.builder()
                .notificationId(record.getId())
                .build();
        dispatchQueue.enqueue(task);
        log.info("[KundliService] Task enqueued for notification id={}", record.getId());

        // 5. Return confirmation
        return KundliResponse.builder()
                .success(true)
                .referenceId(referenceId)
                .message("Your Kundli details have been received. " +
                         "Pandit Aman Bhatore's team will contact you within 24–48 hours.")
                .submittedAt(OffsetDateTime.now())
                .build();
    }

    // ── Utility ──────────────────────────────────────────────────

    private String extractClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip))
            return ip.split(",")[0].trim();
        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isBlank()) return ip.trim();
        return request.getRemoteAddr();
    }
}
