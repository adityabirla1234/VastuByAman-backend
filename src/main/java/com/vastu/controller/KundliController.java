package com.vastu.controller;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.vastu.dto.KundliRequest;
import com.vastu.dto.KundliResponse;
import com.vastu.service.KundliService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;


@Slf4j
@RestController
@RequiredArgsConstructor
public class KundliController {

    private final KundliService kundliService;

    @PostMapping(
        value = "/kundali-check",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<KundliResponse> submitKundli(
            @Valid @RequestBody KundliRequest request,
            HttpServletRequest httpRequest) {

        log.info("[KundliController] New Kundli request – name={} dob={} place={}",
                request.getFullName(), request.getDateOfBirth(), request.getBirthPlace());

        KundliResponse response = kundliService.submitKundli(request, httpRequest);
        return ResponseEntity.ok(response);
    }

    /** Simple health-check endpoint. */
    @GetMapping("/kundli-check/ping")
    public ResponseEntity<String> ping() {
        return ResponseEntity.ok("{\"status\":\"ok\"}");
    }
}
