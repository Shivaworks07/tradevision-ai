package com.tradevision.controller;

import com.tradevision.dto.ApiResponse;
import com.tradevision.service.StockSymbolService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/stocks/symbols")
@RequiredArgsConstructor
// CORS is handled centrally by CorsConfig's global CorsFilter; no per-controller
// @CrossOrigin is needed here.
public class StockSymbolController {

    private final StockSymbolService symbolService;

    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam(required = false, defaultValue = "") String q,
            @RequestParam(defaultValue = "50") int limit) {
        if (limit > 200) limit = 200;
        return ResponseEntity.ok(ApiResponse.ok("Symbols", symbolService.search(q, limit)));
    }
}
