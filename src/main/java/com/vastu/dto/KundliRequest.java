package com.vastu.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Mirrors the KundliCheck.jsx form submission payload.
 *
 * The React component submits JSON (application/json) — not multipart —
 * because there are no file uploads in the Kundli form.
 *
 * JSON shape sent by KundliCheck.jsx handleSubmit():
 * {
 *   "fullName":    "Rahul Sharma",
 *   "gender":      "Male" | "Female" | "Other",
 *   "phone":       "+919876543210",      // countryCode + phone concatenated
 *   "dateOfBirth": "1990-05-15",         // ISO date (input type="date")
 *   "birthPlace":  "Indore, MP, India",
 *   "birthTime":   "06:30"              // HH:mm (input type="time")
 * }
 */
@Data
public class KundliRequest {

    @NotBlank(message = "Full name is required")
    @Size(min = 2, max = 255, message = "Full name must be 2–255 characters")
    private String fullName;

    @NotBlank(message = "Gender is required")
    @Pattern(regexp = "Male|Female|Other", message = "Gender must be Male, Female, or Other")
    private String gender;

    @NotBlank(message = "Phone number is required")
    @Pattern(
        regexp = "^\\+?[0-9\\s\\-().]{7,25}$",
        message = "Valid phone number required"
    )
    private String phone;

    /**
     * ISO date string — "yyyy-MM-dd" — sent from the HTML date input.
     */
    @NotBlank(message = "Date of birth is required")
    @Pattern(
        regexp = "^\\d{4}-\\d{2}-\\d{2}$",
        message = "Date of birth must be in yyyy-MM-dd format"
    )
    private String dateOfBirth;

    @NotBlank(message = "Birth place is required")
    @Size(min = 2, max = 500, message = "Birth place must be 2–500 characters")
    private String birthPlace;

    /**
     * "HH:mm" — 24-hour format from the HTML time input.
     * Stored as a string; the Telegram message builder formats it as 12h.
     */
    @NotBlank(message = "Birth time is required")
    @Pattern(
        regexp = "^([01]\\d|2[0-3]):[0-5]\\d$",
        message = "Birth time must be in HH:mm format"
    )
    private String birthTime;
}
