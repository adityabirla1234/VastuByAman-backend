package com.vastu.service;

import com.vastu.dto.KundliRequest;
import com.vastu.dto.KundliResponse;

import jakarta.servlet.http.HttpServletRequest;

public interface KundliService {

    /**
     * Process a Kundli check submission:
     *  1. Validate the request (Bean Validation handles field-level rules)
     *  2. Build the Telegram notification message
     *  3. Register a PENDING notification in the in-memory state store
     *  4. Enqueue the notification task for async Telegram delivery
     *  5. Return a KundliResponse with a reference ID
     *
     * @param request     the parsed JSON body from KundliCheck.jsx
     * @param httpRequest used to capture IP / User-Agent for audit logging
     * @return KundliResponse with referenceId and confirmation message
     */
    KundliResponse submitKundli(KundliRequest request, HttpServletRequest httpRequest);
}
