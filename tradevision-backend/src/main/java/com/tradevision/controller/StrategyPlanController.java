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
 * User's own explicit multi-strategy-plan design, full context in StrategyPlan's own class
 * javadoc: "the next major feature: Strategy Plan Engine." Everything up to this controller has
 * been the engine itself (the model, per-plan scanning, two-tier risk, the exit-policy checks) --
 * this is the actual API surface that lets a user create, edit, enable/disable, list, and delete
 * their own plans, which until now only existed as something reachable by inserting MongoDB
 * documents directly. Follows this codebase's own established controller conventions exactly
 * (BrokerController's own @AuthenticationPrincipal + ApiResponse pattern), not a new convention.
 */
@RestController
@RequestMapping("/api/strategy-plans")
@RequiredArgsConstructor
// Review finding ("@CrossOrigin still has hardcoded localhost origins" -- external review,
// thirty-fifth pass, P2, full context in NewsController's own identical fix): removed --
// CorsConfig's own global CorsFilter already covers this endpoint.
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
            // Review finding ("StrategyPlan update() / setEnabled() can lose concurrent changes"
            // -- external review, fifteenth pass, P0, full context in StrategyPlan.version's own
            // field javadoc): a genuine 409 -- someone else changed this exact plan since this
            // request read it. The request itself is well-formed; retrying against the plan's
            // current state (a fresh GET, then re-apply this edit) is the correct client action,
            // same shape as the existing open-positions delete conflict below.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(
                "This strategy plan was changed by another request just now -- please reload it and try again."));
        }
    }

    /** User's own explicit design: "Turn individual strategy plans ON/OFF." A focused endpoint for exactly that, rather than requiring a full update payload just to flip one flag. */
    @PatchMapping("/{id}/enabled")
    public ResponseEntity<?> setEnabled(@AuthenticationPrincipal String userId, @PathVariable String id,
                                         @RequestParam boolean enabled) {
        try {
            StrategyPlan plan = strategyPlanService.setEnabled(userId, id, enabled);
            return ResponseEntity.ok(ApiResponse.ok("Strategy plan \"" + plan.getName() + "\" " + (enabled ? "enabled" : "disabled") + ".", plan));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(e.getMessage()));
        } catch (org.springframework.dao.OptimisticLockingFailureException e) {
            // Same conflict as update()'s own catch above -- setEnabled() is a save() too now.
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
            // User's own explicit design ("DELETE plan + open positions -> REJECT"), full
            // context in StrategyPlanService.delete's own updated javadoc: 409 Conflict -- the
            // request is well-formed and the plan exists, but deleting it right now would leave
            // an open position with no plan to look up its own exit-management rules from.
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(e.getMessage()));
        }
    }
}
