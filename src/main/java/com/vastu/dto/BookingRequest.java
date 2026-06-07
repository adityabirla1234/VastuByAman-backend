package com.vastu.dto;


import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Mirrors the two Zod schemas from ConsultationBooking.jsx.
 *
 * Fields that are only required for one consultation type are validated
 * programmatically inside ConsultationService.validateRequest() so we can
 * return meaningful per-field errors to the React frontend.
 */
@Data
public class BookingRequest {

    /**
     * "online" or "offline" — matches the consultationType appended by the
     * React FormData: payload.append("consultationType", consultType)
     */
    @NotBlank(message = "Consultation type is required")
    @Pattern(regexp = "online|offline", message = "Must be 'online' or 'offline'")
    private String consultationType;

    // ── Shared fields ──────────────────────────────────────────

    @NotBlank(message = "Full name is required")
    @Size(min = 2, max = 255, message = "Full name must be 2–255 characters")
    private String fullName;

    @NotBlank(message = "Phone number is required")
    @Pattern(
        regexp = "^\\+?[0-9\\s\\-().]{7,20}$",
        message = "Valid phone number required"
    )
    private String phone;

    @NotBlank(message = "Address is required")
    @Size(min = 5, max = 2000, message = "Address must be at least 5 characters")
    private String address;

    @NotBlank(message = "Profession is required")
    @Size(min = 2, max = 255, message = "Profession must be 2–255 characters")
    private String profession;

    // ── Online-only ────────────────────────────────────────────

    /** House degree photo (required for ONLINE) */
    private MultipartFile degreePhoto;

    /** 2D map file – image or PDF (required for ONLINE) */
    private MultipartFile map2D;

    // ── Offline-only ───────────────────────────────────────────

    /** Front site photo (required for OFFLINE) */
    private MultipartFile siteFront;
}
