package com.tradevision.controller;

import com.tradevision.dto.*;
import com.tradevision.service.TradeCallService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Review finding ("Frontend authentication migration is incomplete and currently breaks
 * authenticated APIs" -- P0): confirmed real and fixed -- see UserController's own javadoc for
 * the full root-cause explanation. This controller's own manual `token == null || !jwt.isValid`
 * checks (save/cancel/bySymbol/recent) are no longer needed at all: a null @AuthenticationPrincipal
 * IS the "not authenticated" signal now, and every endpoint here sits behind
 * `.anyRequest().authenticated()` in SecurityConfig regardless, so Spring Security's own
 * authorization filter already rejects a genuinely unauthenticated request before reaching here
 * -- this class doesn't need to re-verify a token Spring Security already verified.
 */
@RestController
@RequestMapping("/api/calls")
@RequiredArgsConstructor
// Review finding ("@CrossOrigin still has hardcoded localhost origins" -- external review,
// thirty-fifth pass, P2, full context in NewsController's own identical fix): removed --
// CorsConfig's own global CorsFilter already covers this endpoint.
public class TradeCallController {

    private final TradeCallService callService;

    @PostMapping("/save")
    public ResponseEntity<?> save(@AuthenticationPrincipal String userId, @Valid @RequestBody TradeCallRequest req) {
        return ResponseEntity.ok(callService.saveCall(userId, req));
    }

    /**
     * Review finding ("Signal lifecycle is still partial" — "CANCELLED still unused"): the
     * actual endpoint — kept thin, same pattern as every other controller in this codebase; all
     * real logic (ownership check, cancellability, the atomic conditional write) lives in
     * TradeCallService.cancelSignal.
     */
    @PostMapping("/{callId}/cancel")
    public ResponseEntity<?> cancel(@AuthenticationPrincipal String userId, @PathVariable String callId) {
        return ResponseEntity.ok(callService.cancelSignal(userId, callId));
    }

    @GetMapping("/symbol")
    public ResponseEntity<?> bySymbol(@AuthenticationPrincipal String userId, @RequestParam String symbol) {
        return ResponseEntity.ok(callService.getCallsForSymbol(userId, symbol));
    }

    @GetMapping("/recent")
    public ResponseEntity<?> recent(@AuthenticationPrincipal String userId, @RequestParam(defaultValue="10") int limit) {
        return ResponseEntity.ok(callService.getRecentCalls(userId, limit));
    }

    @PostMapping("/result")
    public ResponseEntity<?> updateResult(@AuthenticationPrincipal String userId, @RequestBody TradeCallResultRequest req) {
        return ResponseEntity.ok(callService.updateResult(userId, req));
    }

    @GetMapping("/stats")
    public ResponseEntity<?> stats(@AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(callService.getStats(userId));
    }

    @GetMapping("/analytics")
    public ResponseEntity<?> analytics(@AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(callService.getStrategyAnalytics(userId));
    }

    @GetMapping("/calibration")
    public ResponseEntity<?> calibration(@AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(callService.getConfidenceCalibration(userId));
    }

    @GetMapping("/walkforward")
    public ResponseEntity<?> walkForward(@AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(callService.getWalkForward(userId));
    }

    // ── Correlation Analysis ─────────────────────────────────
    @GetMapping("/correlation")
    public ResponseEntity<?> correlation(@AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(callService.getCorrelationData(userId));
    }

    // ── Strategy Comparison ───────────────────────────────────
    @GetMapping("/comparison")
    public ResponseEntity<?> comparison(@AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(callService.getStrategyComparison(userId));
    }

    // ── ML Dataset Export: CSV ────────────────────────────────
    /**
     * Review finding ("Some analytics are deliberately bounded rather than truly paginated" --
     * external review, thirty-sixth pass, P2, full context in TradeCallService.exportCSVPage's
     * own updated javadoc): this endpoint used to always return exactly the first 1000 resolved
     * calls, with no way to reach anything beyond that at all. Added a real page parameter --
     * defaults to 0 (page size fixed at 1000, matching this endpoint's own established size)
     * so an existing caller passing no page parameter gets EXACTLY the same first-1000-records
     * response as before this fix, byte-for-byte, and a caller that wants more can now genuinely
     * ask for it via ?page=1, ?page=2, etc. Pagination metadata (total record count, whether a
     * further page exists) is exposed via response headers rather than changing the response
     * body's own format, which stays a plain CSV file either way.
     */
    @GetMapping("/ml/export/csv")
    public ResponseEntity<byte[]> exportCSV(@AuthenticationPrincipal String userId, @RequestParam(defaultValue = "0") int page) {
        String datasetId = java.util.UUID.randomUUID().toString().substring(0, 8);
        var csvPage       = callService.exportCSVPage(userId, page, 1000);
        // Prepend dataset metadata as comment line
        String withMeta  = "# tradevision_dataset | id=" + datasetId +
                           " | exported=" + java.time.LocalDateTime.now() +
                           " | schema=v2\n" + csvPage.page();
        byte[] data = withMeta.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // Review finding (P1 #24 — "CSV dataset hash is still not cryptographic"): this is called
        // a dataset INTEGRITY hash — Integer.hashCode() (32-bit, not cryptographic, real
        // collision risk) undermines that claim. SHA-256, same fix as the idempotency key above.
        String hash;
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            hash = sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            hash = "unavailable"; // never happens on any real JVM — fails safe rather than throwing on an export
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv; charset=UTF-8"));
        headers.set("Content-Disposition", "attachment; filename=tradevision_" + datasetId + "_page" + page + ".csv");
        headers.set("X-Dataset-Id",   datasetId);
        headers.set("X-Dataset-Hash", hash);
        headers.set("X-Page", String.valueOf(page));
        headers.set("X-Total-Records", String.valueOf(csvPage.totalRecords()));
        headers.set("X-Has-Next-Page", String.valueOf(csvPage.hasNext()));
        headers.setContentLength(data.length);
        return ResponseEntity.ok().headers(headers).body(data);
    }

    // ── ML Dataset Export: JSON ───────────────────────────────
    @GetMapping("/ml/export/json")
    public ResponseEntity<byte[]> exportJSON(@AuthenticationPrincipal String userId) {
        byte[] data = callService.exportJSON(userId).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Content-Disposition", "attachment; filename=tradevision_dataset.json");
        headers.setContentLength(data.length);
        return ResponseEntity.ok().headers(headers).body(data);
    }

    // ── Legacy endpoint (keep for backward compat) ────────────
    @GetMapping("/ml/export")
    public ResponseEntity<byte[]> exportMLLegacy(@AuthenticationPrincipal String userId) {
        return exportCSV(userId, 0);
    }
}
