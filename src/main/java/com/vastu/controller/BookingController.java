package com.vastu.controller;


import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.vastu.dto.BookingRequest;
import com.vastu.dto.BookingResponse;
import com.vastu.service.ConsultationService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequiredArgsConstructor
public class BookingController {

    private final ConsultationService consultationService;

    @PostMapping(
        value = "/book-consultation",
        consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<BookingResponse> bookConsultation(
            @Valid @ModelAttribute BookingRequest request,
            HttpServletRequest httpRequest) {

        log.info("[BookingController] New booking request – type={} name={}",
                request.getConsultationType(), request.getFullName());

        BookingResponse response = consultationService.submitBooking(request, httpRequest);
        return ResponseEntity.ok(response);
    }

    /** Simple health-check endpoint for the frontend to test connectivity. */
    @GetMapping("/book-consultation/ping")
    public ResponseEntity<String> ping() {
        return ResponseEntity.ok("{\"status\":\"ok\"}");
    }
}

