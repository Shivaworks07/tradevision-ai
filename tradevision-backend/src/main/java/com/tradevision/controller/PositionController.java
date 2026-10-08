package com.tradevision.controller;

import com.tradevision.dto.ApiResponse;
import com.tradevision.service.PositionDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Exposes a user's open/closed positions, manual emergency-flatten and reconcile-now actions,
 * and unresolved incidents for a credential. Kept thin: ownership checks and what's safe to
 * expose as a manual action live in PositionDashboardService.
 */
@RestController
@RequestMapping("/api/positions")
@RequiredArgsConstructor
public class PositionController {

    private final PositionDashboardService dashboardService;

    @GetMapping("/{credentialId}")
    public ResponseEntity<?> list(@AuthenticationPrincipal String userId, @PathVariable String credentialId,
                                   @RequestParam(required = false) String status,
                                   // Paginated so a credential with a long history of closed
                                   // positions doesn't return thousands of records in one
                                   // response; callers that need more than the first page must
                                   // page through explicitly.
                                   @RequestParam(required = false, defaultValue = "0") int page,
                                   @RequestParam(required = false, defaultValue = "50") int size) {
        try {
            return ResponseEntity.ok(ApiResponse.ok("OK", dashboardService.listPositions(userId, credentialId, status, page, size)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        }
    }

    @PostMapping("/{positionId}/emergency-flatten")
    public ResponseEntity<?> emergencyFlatten(@AuthenticationPrincipal String userId, @PathVariable String positionId) {
        try {
            dashboardService.emergencyFlattenPosition(userId, positionId);
            return ResponseEntity.ok(ApiResponse.ok("Emergency flatten requested — check the position status shortly for the outcome.", null));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            String refId = "TV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            org.slf4j.LoggerFactory.getLogger(PositionController.class)
                .error("Manual emergency flatten failed [{}]: {}", refId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error("Emergency flatten request failed. Reference ID: " + refId));
        }
    }

    @PostMapping("/credential/{credentialId}/reconcile-now")
    public ResponseEntity<?> reconcileNow(@AuthenticationPrincipal String userId, @PathVariable String credentialId) {
        try {
            dashboardService.reconcileNow(userId, credentialId);
            return ResponseEntity.ok(ApiResponse.ok("Reconciliation triggered — refresh shortly to see updated status.", null));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        } catch (Exception e) {
            String refId = "TV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            org.slf4j.LoggerFactory.getLogger(PositionController.class)
                .error("Manual reconcile failed [{}]: {}", refId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponse.error("Reconcile request failed. Reference ID: " + refId));
        }
    }

    @GetMapping("/credential/{credentialId}/incidents")
    public ResponseEntity<?> unresolvedIncidents(@AuthenticationPrincipal String userId, @PathVariable String credentialId) {
        try {
            return ResponseEntity.ok(ApiResponse.ok("OK", dashboardService.unresolvedIncidents(userId, credentialId)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        }
    }
}
