package com.tradevision.controller;

import com.tradevision.dto.ApiResponse;
import com.tradevision.service.NewsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/news")
@RequiredArgsConstructor
// Review finding ("@CrossOrigin still has hardcoded localhost origins" -- external review,
// thirty-fifth pass, P2, confirmed real by direct inspection before this fix: this
// controller-level annotation hardcoded localhost origins independently of
// CorsConfig.allowedOrigins (driven by the configurable app.cors.allowed-origins property),
// meaning production would need BOTH the property AND every one of these annotations updated
// to change allowed origins -- exactly the "two sources of truth" risk the review names):
// removed. CorsConfig's own global CorsFilter, registered for "/**", already covers every
// endpoint in this application, including this controller's own -- this annotation was
// redundant at best, and a second, unsynchronized origin list at worst.
public class NewsController {

    private final NewsService newsService;

    @GetMapping
    public ResponseEntity<?> getNews(
            @RequestParam(defaultValue = "ALL") String category,
            @RequestParam(defaultValue = "30")  int    limit) {
        if (limit > 100) limit = 100;
        return ResponseEntity.ok(ApiResponse.ok("News", newsService.getNews(category, limit)));
    }
}
