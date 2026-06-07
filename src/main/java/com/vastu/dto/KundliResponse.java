package com.vastu.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * Returned to KundliCheck.jsx after a successful submission.
 *
 * The React success screen shows the referenceId and a thank-you message.
 * Keep the shape consistent with BookingResponse so the frontend can handle
 * both the same way if needed.
 */
@Data
@Builder
public class KundliResponse {

    private boolean success;

    /** Short 8-character uppercase reference shown to the user. */
    private String referenceId;

    /** Human-readable confirmation message. */
    private String message;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ssXXX")
    private OffsetDateTime submittedAt;
}
