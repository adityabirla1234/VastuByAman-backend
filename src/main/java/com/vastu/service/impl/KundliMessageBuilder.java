package com.vastu.service.impl;

import org.springframework.stereotype.Component;

import com.vastu.dto.KundliRequest;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Builds the pre-formatted Telegram message text for a Kundli check submission.
 *
 * Mirrors the style of TelegramMessageBuilder (used for consultations).
 *
 * Raw text is stored without MarkdownV2 escaping — escaping is applied by
 * TelegramApiService.escapeMarkdownV2() immediately before the HTTP call,
 * ensuring it happens exactly once.
 */
@Component
public class KundliMessageBuilder {

    private static final DateTimeFormatter SUBMIT_FORMATTER =
            DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a");

    private static final DateTimeFormatter DOB_FORMATTER =
            DateTimeFormatter.ofPattern("dd MMM yyyy");

    private static final DateTimeFormatter TIME_FORMATTER_IN  =
            DateTimeFormatter.ofPattern("HH:mm");

    private static final DateTimeFormatter TIME_FORMATTER_OUT =
            DateTimeFormatter.ofPattern("hh:mm a");

    /**
     * Produces a well-formatted Kundli check notification for Pandit Aman Bhatore's Telegram chat.
     *
     * @param referenceId   short 8-char uppercase booking ID
     * @param request       the validated KundliRequest
     * @param ipAddress     client IP (may be null)
     * @param userAgent     browser User-Agent (may be null)
     * @return raw message text (not MarkdownV2-escaped)
     */
    public String buildMessage(
            String referenceId,
            KundliRequest request,
            String ipAddress,
            String userAgent) {

        String submittedAt = OffsetDateTime.now().format(SUBMIT_FORMATTER);
        String dob         = formatDob(request.getDateOfBirth());
        String birthTime   = formatTime(request.getBirthTime());

        StringBuilder sb = new StringBuilder();
        sb.append("🪐 NEW KUNDLI CHECK REQUEST\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━\n\n");
        sb.append("🔖 Ref:       ").append(referenceId).append("\n");
        sb.append("📅 Submitted: ").append(submittedAt).append("\n\n");

        sb.append("👤 PERSONAL DETAILS\n");
        sb.append("─────────────────\n");
        sb.append("Name:       ").append(request.getFullName().trim()).append("\n");
        sb.append("Gender:     ").append(request.getGender()).append("\n");
        sb.append("Phone:      ").append(request.getPhone().trim()).append("\n\n");

        sb.append("🌟 BIRTH DETAILS\n");
        sb.append("─────────────────\n");
        sb.append("Date:       ").append(dob).append("\n");
        sb.append("Time:       ").append(birthTime).append("\n");
        sb.append("Place:      ").append(request.getBirthPlace().trim()).append("\n");

        sb.append("\n━━━━━━━━━━━━━━━━━━━━━━\n");
        
        return sb.toString();
    }

    // ── Helpers ──────────────────────────────────────────────────

    /**
     * Converts "1990-05-15" → "15 May 1990".
     * Falls back to the raw string if parsing fails.
     */
    private String formatDob(String iso) {
        if (iso == null || iso.isBlank()) return "—";
        try {
            java.time.LocalDate date = java.time.LocalDate.parse(iso,
                    java.time.format.DateTimeFormatter.ISO_LOCAL_DATE);
            return date.format(DOB_FORMATTER);
        } catch (Exception e) {
            return iso;
        }
    }

    /**
     * Converts "06:30" (24h) → "06:30 AM" (12h).
     * Falls back to the raw string if parsing fails.
     */
    private String formatTime(String hhmm) {
        if (hhmm == null || hhmm.isBlank()) return "—";
        try {
            LocalTime t = LocalTime.parse(hhmm, TIME_FORMATTER_IN);
            return t.format(TIME_FORMATTER_OUT);
        } catch (Exception e) {
            return hhmm;
        }
    }
}
