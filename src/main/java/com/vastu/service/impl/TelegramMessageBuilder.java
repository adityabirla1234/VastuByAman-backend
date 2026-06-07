package com.vastu.service.impl;



import org.springframework.stereotype.Component;

import com.vastu.dto.BookingRequest;
import com.vastu.enums.ConsultationType;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Builds the pre-formatted Telegram message text for a booking.
 *
 * Updated to accept flat parameters instead of a JPA entity, since
 * there is no longer a database or entity layer.
 *
 * Raw text is stored without MarkdownV2 escaping — escaping is applied
 * by TelegramApiService.escapeMarkdownV2() immediately before the HTTP
 * call so it happens exactly once.
 */
@Component
public class TelegramMessageBuilder {

    private static final DateTimeFormatter FORMATTER =
            DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a");

    /**
     * Produces a well-formatted notification for Pandit Aman Bhatore's Telegram chat.
     */
    public String buildMessage(
            String referenceId,
            ConsultationType type,
            BookingRequest request,
            String degreePhotoPath,
            String map2DPath,
            String siteFrontPath,
            String ipAddress,
            String userAgent) {

        boolean isOnline = type == ConsultationType.ONLINE;
        String typeLabel = isOnline ? "🌐 Online" : "🏠 Offline";
        String submittedAt = OffsetDateTime.now().format(FORMATTER);

        StringBuilder sb = new StringBuilder();
        sb.append("🪷 NEW CONSULTATION BOOKING\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━\n\n");
        sb.append("📋 Type: ").append(typeLabel).append("\n");
        sb.append("🔖 Ref: ").append(referenceId).append("\n");
        sb.append("📅 Submitted: ").append(submittedAt).append("\n\n");

        sb.append("👤 PERSONAL DETAILS\n");
        sb.append("─────────────────\n");
        sb.append("Name:       ").append(request.getFullName().trim()).append("\n");
        sb.append("Phone:      ").append(request.getPhone().trim()).append("\n");
        sb.append("Address:    ").append(request.getAddress().trim()).append("\n");
        sb.append("Profession: ").append(request.getProfession().trim()).append("\n\n");

        sb.append("📎 DOCUMENTS\n");
        sb.append("─────────────────\n");
        if (isOnline) {
            sb.append("House Degree Photo: ").append(degreePhotoPath != null ? "✅ Uploaded" : "❌ Missing").append("\n");
            sb.append("2D Map:             ").append(map2DPath       != null ? "✅ Uploaded" : "❌ Missing").append("\n");
        } else {
            sb.append("Site Front Photo: ").append(siteFrontPath != null ? "✅ Uploaded" : "❌ Missing").append("\n");
        }

        if (ipAddress != null) {
            sb.append("\n🔒 IP: ").append(ipAddress).append("\n");
        }

        sb.append("\n━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("📂 Files attached in next message(s)");

        return sb.toString();
    }
}
