package com.vastu.exception;



import java.util.Map;

import lombok.Getter;

/**
 * Thrown when per-type file validation fails inside ConsultationServiceImpl.
 * Caught by GlobalExceptionHandler and mapped to HTTP 400 with fieldErrors.
 */
@Getter
public class BookingValidationException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    public BookingValidationException(String message, Map<String, String> fieldErrors) {
        super(message);
        this.fieldErrors = fieldErrors;
    }
}

