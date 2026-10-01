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
 * Review finding ("UI has no real Position/Execution dashboard" / "No user-facing emergency
 * position action"): kept deliberately thin — every actual decision (ownership checks, what's
 * safe to expose as a manual action) lives in PositionDashboardService, same pattern as every
 * other controller in this codebase.
 */
@RestController
@RequestMapping("/api/positions")
@RequiredArgsConstructor
public class PositionController {

    private final PositionDashboardService dashboardService;

    @GetMapping("/{credentialId}")
    public ResponseEntity<?> list(@AuthenticationPrincipal String userId, @PathVariable String credentialId,
                                   @RequestParam(required = false) String status,
                                   // Review finding ("Pagination for order history/positions/
                                   // metrics" -- P2): confirmed real -- this endpoint used to
                                   // return every position ever opened for this credential, with
                                   // no limit at all. A credential open for months could
                                   // eventually return thousands of closed positions on a single
                                   // request. HONEST NOTE: defaulting page/size to 0/50 (rather
                                   // than defaulting to "no limit" and only bounding when a
                                   // caller explicitly asks) is a genuine behavior change for
                                   // this existing endpoint, not silently backward-compatible --
                                   // a default of "unbounded unless asked" would leave the
                                   // actual problem unfixed for the current frontend, which
                                   // doesn't pass these params yet. The frontend may need
                                   // corresponding UI work (a "load more"/pager control) to
                                   // surface access to anything past the first 50 -- not
                                   // attempted here, scoped to the backend response-size fix.
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
