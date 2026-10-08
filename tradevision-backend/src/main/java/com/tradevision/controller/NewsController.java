package com.tradevision.controller;

import com.tradevision.dto.ApiResponse;
import com.tradevision.service.NewsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/news")
@RequiredArgsConstructor
// CORS is handled centrally by CorsConfig's global CorsFilter (driven by the
// app.cors.allowed-origins property), so no per-controller @CrossOrigin is needed here.
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
