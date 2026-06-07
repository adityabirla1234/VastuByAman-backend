package com.vastu.dto;



import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * Returned to the React frontend after a successful submission.
 * The referenceId is displayed in the SuccessScreen component.
 */
@Data
@Builder
public class BookingResponse {

    private boolean success;
    private String referenceId;
    private String message;

    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ssXXX")
    private OffsetDateTime submittedAt;
}

