package com.vastu.service;


import com.vastu.dto.BookingRequest;
import com.vastu.dto.BookingResponse;

import jakarta.servlet.http.HttpServletRequest;

public interface ConsultationService {

    /**
     * Process a consultation booking:
     *  1. Validate request fields based on consultation type
     *  2. Store uploaded files on disk
     *  3. Persist the booking to the database
     *  4. Create a TelegramNotification outbox row (same transaction)
     *  5. Enqueue the notification task for async Telegram delivery
     *
     * @param request    the submitted form data
     * @param httpRequest used to capture IP address and User-Agent for audit
     * @return BookingResponse with the generated referenceId
     */
    BookingResponse submitBooking(BookingRequest request, HttpServletRequest httpRequest);
}

