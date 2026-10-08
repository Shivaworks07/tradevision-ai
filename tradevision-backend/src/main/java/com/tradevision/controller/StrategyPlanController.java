package com.tradevision.controller;

import com.tradevision.dto.ApiResponse;
import com.tradevision.dto.StrategyPlanRequest;
import com.tradevision.model.StrategyPlan;
import com.tradevision.service.StrategyPlanService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Lets a user create, edit, enable/disable, list, and delete their own strategy plans -- the
 * API surface over the strategy-plan engine (per-plan scanning, two-tier risk, exit-policy
 * checks). Follows the same @AuthenticationPrincipal + ApiResponse pattern used throughout this
 * codebase's other controllers.
 */
@RestController
@RequestMapping("/api/strategy-plans")
@RequiredArgsConstructor
// CORS is handled centrally by CorsConfig's global CorsFilter; no per-controller
// @CrossOrigin is needed here.
public class StrategyPlanController {

    private final StrategyPlanService strategyPlanService;

    @PostMapping
    public ResponseEntity<?> create(@AuthenticationPrincipal String userId, @Valid @RequestBody StrategyPlanRequest req) {
        try {
            StrategyPlan plan = strategyPlanService.create(userId, req);
            return ResponseEntity.ok(ApiResponse.ok("Strategy plan \"" + plan.getName() + "\" created.", plan));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<?> list(@AuthenticationPrincipal String userId, @RequestParam String credentialId) {
        return ResponseEntity.ok(ApiResponse.ok("OK", strategyPlanService.list(userId, credentialId)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@AuthenticationPrincipal String userId, @PathVariable String id,
                                     @Valid @RequestBody StrategyPlanRequest req) {
        try {
            StrategyPlan plan = strategyPlanService.update(userId, id, req);
            return ResponseEntity.ok(ApiResponse.ok("Strategy plan \"" + plan.getName() + "\" updated.", plan));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(e.getMessage()));
        } catch (org.springframework.dao.OptimisticLockingFailureException e) {
            // Optimistic-locking conflict: someone else changed this exact plan since this
            // request read it. The request itself is well-formed; the client should re-fetch
            // the plan's current state and re-apply the edit.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(
                "This strategy plan was changed by another request just now -- please reload it and try again."));
        }
    }

    /** Toggles a single strategy plan on or off without requiring a full update payload. */
    @PatchMapping("/{id}/enabled")
    public ResponseEntity<?> setEnabled(@AuthenticationPrincipal String userId, @PathVariable String id,
                                         @RequestParam boolean enabled) {
        try {
            StrategyPlan plan = strategyPlanService.setEnabled(userId, id, enabled);
            return ResponseEntity.ok(ApiResponse.ok("Strategy plan \"" + plan.getName() + "\" " + (enabled ? "enabled" : "disabled") + ".", plan));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        } catch (org.springframework.dao.OptimisticLockingFailureException e) {
            // Same optimistic-locking conflict as update() above, since setEnabled() also saves.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(
                "This strategy plan was changed by another request just now -- please reload it and try again."));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@AuthenticationPrincipal String userId, @PathVariable String id) {
        try {
            strategyPlanService.delete(userId, id);
            return ResponseEntity.ok(ApiResponse.ok("Strategy plan removed."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        } catch (IllegalStateException e) {
            // The plan exists but still has open positions relying on it; deleting it now would
            // leave those positions with no plan to look up exit-management rules from.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(e.getMessage()));
        }
    }
}
