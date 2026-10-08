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
 * Manages trade call signals: saving new calls, cancelling pending ones, querying by symbol or
 * recency, recording outcomes, and exposing performance analytics and ML dataset exports.
 *
 * Identity comes from Spring Security's SecurityContext via @AuthenticationPrincipal; every
 * endpoint here sits behind `.anyRequest().authenticated()` in SecurityConfig, which rejects an
 * unauthenticated request before it reaches this controller.
 */
@RestController
@RequestMapping("/api/calls")
@RequiredArgsConstructor
// CORS is handled centrally by CorsConfig's global CorsFilter; no per-controller
// @CrossOrigin is needed here.
public class TradeCallController {

    private final TradeCallService callService;

    @PostMapping("/save")
    public ResponseEntity<?> save(@AuthenticationPrincipal String userId, @Valid @RequestBody TradeCallRequest req) {
        return ResponseEntity.ok(callService.saveCall(userId, req));
    }

    /**
     * Cancels a pending trade call signal. Ownership and cancellability checks, plus the atomic
     * conditional write, live in TradeCallService.cancelSignal.
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
     * Exports resolved trade calls as a CSV page for ML/analysis use. Page size is fixed at
     * 1000 records; the default page (0) returns the most recent 1000, and further pages are
     * reachable via ?page=1, ?page=2, etc. Pagination metadata (total record count, whether a
     * further page exists) travels in response headers rather than the CSV body, which stays a
     * plain CSV file either way.
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
        // SHA-256 integrity hash of the exported bytes, so a consumer can verify the dataset
        // wasn't altered or truncated in transit.
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
