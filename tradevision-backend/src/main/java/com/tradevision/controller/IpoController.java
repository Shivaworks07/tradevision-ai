package com.tradevision.controller;

import com.tradevision.dto.ApiResponse;
import com.tradevision.service.IpoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ipo")
@RequiredArgsConstructor
// CORS is handled centrally by CorsConfig's global CorsFilter; no per-controller
// @CrossOrigin is needed here.
public class IpoController {

    private final IpoService ipoService;

    @GetMapping
    public ResponseEntity<?> getIpos(@RequestParam(defaultValue = "ALL") String status) {
        return ResponseEntity.ok(ApiResponse.ok("IPOs", ipoService.getIpos(status)));
    }
}
