package com.tradevision.controller;

import com.tradevision.dto.ApiResponse;
import com.tradevision.service.OpsStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes an operational status summary for monitoring. Kept thin: the actual status
 * computation lives in OpsStatusService.
 */
@RestController
@RequestMapping("/api/ops")
@RequiredArgsConstructor
public class OpsStatusController {

    private final OpsStatusService opsStatusService;

    @GetMapping("/status")
    public ResponseEntity<?> status(@AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(ApiResponse.ok("OK", opsStatusService.getStatus(userId)));
    }
}
