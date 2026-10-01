package com.tradevision.controller;

import com.tradevision.dto.ApiResponse;
import com.tradevision.service.IpoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ipo")
@RequiredArgsConstructor
// Review finding ("@CrossOrigin still has hardcoded localhost origins" -- external review,
// thirty-fifth pass, P2, full context in NewsController's own identical fix): removed --
// CorsConfig's own global CorsFilter already covers this endpoint.
public class IpoController {

    private final IpoService ipoService;

    @GetMapping
    public ResponseEntity<?> getIpos(@RequestParam(defaultValue = "ALL") String status) {
        return ResponseEntity.ok(ApiResponse.ok("IPOs", ipoService.getIpos(status)));
    }
}
