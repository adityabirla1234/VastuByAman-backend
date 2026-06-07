package com.vastu.exception;


import java.util.HashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.vastu.dto.ErrorResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * Centralised exception handling for all REST controllers.
 *
 * Maps exceptions to structured JSON responses that the React frontend
 * can display per-field via react-hook-form's setError().
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Bean validation errors (@Valid on the controller method parameter). */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidationErrors(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(fe.getField(), fe.getDefaultMessage());
        }
        log.warn("[Validation] Bean validation failed: {}", fieldErrors);
        return ResponseEntity.badRequest().body(ErrorResponse.builder()
                .success(false)
                .error("VALIDATION_ERROR")
                .message("Please fix the highlighted fields and try again.")
                .fieldErrors(fieldErrors)
                .build());
    }

    /** Custom per-type file validation errors thrown by the service layer. */
    @ExceptionHandler(BookingValidationException.class)
    public ResponseEntity<ErrorResponse> handleBookingValidation(BookingValidationException ex) {
        log.warn("[Validation] Booking validation failed: {}", ex.getFieldErrors());
        return ResponseEntity.badRequest().body(ErrorResponse.builder()
                .success(false)
                .error("BOOKING_VALIDATION_ERROR")
                .message(ex.getMessage())
                .fieldErrors(ex.getFieldErrors())
                .build());
    }

    /** Multipart file too large (checked by Spring before reaching the controller). */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        log.warn("[Upload] File size exceeded: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(ErrorResponse.builder()
                .success(false)
                .error("FILE_TOO_LARGE")
                .message("One or more uploaded files exceed the maximum allowed size of 10 MB.")
                .build());
    }

    /** Illegal argument from service layer (e.g. disallowed MIME type). */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("[Validation] Illegal argument: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(ErrorResponse.builder()
                .success(false)
                .error("INVALID_INPUT")
                .message(ex.getMessage())
                .build());
    }

    /** Catch-all for unexpected server errors. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex) {
        log.error("[Error] Unexpected exception", ex);
        return ResponseEntity.internalServerError().body(ErrorResponse.builder()
                .success(false)
                .error("INTERNAL_ERROR")
                .message("An unexpected error occurred. Please try again later.")
                .build());
    }
}
