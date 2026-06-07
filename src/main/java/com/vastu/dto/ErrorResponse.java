package com.vastu.dto;


import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * Standardised error envelope returned by GlobalExceptionHandler.
 * The React frontend can map fieldErrors back to react-hook-form setError().
 */
@Data
@Builder
public class ErrorResponse {
    private boolean success;
    private String error;
    private String message;

    /** Field-level validation errors, keyed by field name. */
    private Map<String, String> fieldErrors;
}

