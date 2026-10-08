package com.tradevision.controller;

import com.tradevision.dto.ApiResponse;
import com.tradevision.model.Feedback;
import com.tradevision.repository.FeedbackRepository;
import com.tradevision.repository.UserRepository;
import com.tradevision.service.DistributedRateLimitService;
import com.tradevision.util.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Handles user-submitted feedback (bug reports, suggestions, with an optional screenshot),
 * plus the admin views and actions for triaging it.
 *
 * submit() is a public endpoint (SecurityConfig permits /api/feedback without authentication)
 * that still records which user sent it when the request happens to carry an authenticated
 * session: @AuthenticationPrincipal is simply null for an anonymous caller and populated when
 * SecurityConfig's jwtFilter authenticates the request's cookie.
 */
@RestController
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
// CORS is handled centrally by CorsConfig's global CorsFilter; no per-controller
// @CrossOrigin is needed here.
public class FeedbackController {

    private final FeedbackRepository feedbackRepo;
    private final UserRepository     userRepo;
    // Optional because BuildProperties is only registered when the build-info goal actually ran
    // (a full Maven build, not necessarily a raw check or an IDE run); falls back to "dev"
    // rather than failing when it's absent.
    private final java.util.Optional<BuildProperties> buildProperties;
    /**
     * Throttles feedback submissions per client IP, reusing the same DistributedRateLimitService
     * and ClientIpResolver infrastructure used for OTP/account-check endpoints. Since this
     * endpoint is public and accepts a screenshot of up to ~2.8MB base64 per submission, the
     * rate limit together with that size cap bounds storage and write load to a small, known
     * worst case per IP per window.
     */
    private final DistributedRateLimitService distributedRateLimitService;
    private static final int MAX_FEEDBACK_SUBMISSIONS_PER_IP_PER_WINDOW = 30;
    private static final long FEEDBACK_SUBMIT_IP_WINDOW_SECONDS = 60;
    @Value("${app.proxy.trust-forwarded-for:false}")
    private boolean trustForwardedFor;
    @Value("${app.proxy.trusted-proxy-cidrs:}")
    private String trustedProxyCidrs;

    // ── Submit feedback (any logged-in user, or anonymous) ────
    @PostMapping
    public ResponseEntity<?> submit(
            @AuthenticationPrincipal String userId,
            @RequestBody Feedback req,
            HttpServletRequest httpReq) {

        String clientIp = ClientIpResolver.resolve(httpReq, trustForwardedFor, trustedProxyCidrs);
        if (!distributedRateLimitService.allow("feedback_submit_by_ip", clientIp,
                MAX_FEEDBACK_SUBMISSIONS_PER_IP_PER_WINDOW, FEEDBACK_SUBMIT_IP_WINDOW_SECONDS)) {
            return ResponseEntity.status(429).body(ApiResponse.error("Too many feedback submissions from this network. Please try again shortly."));
        }

        Feedback f = new Feedback();
        f.setType(req.getType() != null ? req.getType() : "OTHER");
        f.setTitle(req.getTitle());
        f.setDescription(req.getDescription());
        f.setPage(req.getPage());
        f.setScreenshotBase64(req.getScreenshotBase64());
        f.setScreenshotMime(req.getScreenshotMime());
        f.setUserAgent(req.getUserAgent());
        f.setAppVersion(buildProperties.map(BuildProperties::getVersion).orElse("dev"));
        f.setStatus("OPEN");
        f.setPriority("MEDIUM");

        if (userId != null) {
            f.setUserId(userId);
            userRepo.findById(userId).ifPresent(u -> f.setMobile(u.getMobile()));
        }

        // Validate
        if (f.getTitle() == null || f.getTitle().isBlank())
            return ResponseEntity.badRequest().body(ApiResponse.error("Title is required"));
        if (f.getDescription() == null || f.getDescription().isBlank())
            return ResponseEntity.badRequest().body(ApiResponse.error("Description is required"));
        if (f.getTitle().length() > 200)
            return ResponseEntity.badRequest().body(ApiResponse.error("Title too long (max 200 chars)"));
        // base64 inflates size by ~33% over the raw binary, so this caps the encoded string at
        // roughly what decodes to 2MB of actual image data.
        if (f.getScreenshotBase64() != null && f.getScreenshotBase64().length() > 2_800_000) {
            return ResponseEntity.badRequest().body(ApiResponse.error("Screenshot too large (max ~2MB) — please attach a smaller image."));
        }

        feedbackRepo.save(f);
        return ResponseEntity.ok(ApiResponse.ok("Thank you! Your feedback has been submitted.", Map.of("id", f.getId())));
    }

    // ── My feedback ──────────────────────────────────────────
    @GetMapping("/mine")
    public ResponseEntity<?> mine(@AuthenticationPrincipal String userId) {
        return ResponseEntity.ok(ApiResponse.ok("OK",
            feedbackRepo.findByUserIdOrderByCreatedAtDesc(userId)));
    }

    // ── Admin: list all ──────────────────────────────────────
    @GetMapping("/admin")
    public ResponseEntity<?> adminList(
            @AuthenticationPrincipal String userId,
            @RequestParam(defaultValue="0")    int  page,
            @RequestParam(defaultValue="20")   int  size,
            @RequestParam(defaultValue="")     String status,
            @RequestParam(defaultValue="")     String type) {

        if (!isAdmin(userId)) return forbidden();
        var pg = PageRequest.of(page, size, Sort.by("createdAt").descending());

        var list = !status.isBlank() ? feedbackRepo.findByStatusOrderByCreatedAtDesc(status, pg)
                 : !type.isBlank()   ? feedbackRepo.findByTypeOrderByCreatedAtDesc(type, pg)
                 : feedbackRepo.findByOrderByCreatedAtDesc(pg);

        var stats = Map.of(
            "total",      feedbackRepo.count(),
            "open",       feedbackRepo.countByStatus("OPEN"),
            "inReview",   feedbackRepo.countByStatus("IN_REVIEW"),
            "resolved",   feedbackRepo.countByStatus("RESOLVED"),
            "bugs",       feedbackRepo.countByType("BUG"),
            "suggestions",feedbackRepo.countByType("SUGGESTION")
        );
        return ResponseEntity.ok(ApiResponse.ok("OK", Map.of("items", list, "stats", stats)));
    }

    // ── Admin: update status/priority/note ───────────────────
    @PatchMapping("/admin/{id}")
    public ResponseEntity<?> adminUpdate(
            @AuthenticationPrincipal String userId,
            @PathVariable String id,
            @RequestBody Map<String,String> body) {

        if (!isAdmin(userId)) return forbidden();
        return feedbackRepo.findById(id).map(f -> {
            if (body.containsKey("status"))    f.setStatus(body.get("status"));
            if (body.containsKey("priority"))  f.setPriority(body.get("priority"));
            if (body.containsKey("adminNote")) f.setAdminNote(body.get("adminNote"));
            f.setUpdatedAt(LocalDateTime.now());
            feedbackRepo.save(f);
            return ResponseEntity.ok((Object) ApiResponse.ok("Updated", f));
        }).orElse(ResponseEntity.notFound().build());
    }

    // ── Admin: delete ────────────────────────────────────────
    @DeleteMapping("/admin/{id}")
    public ResponseEntity<?> adminDelete(
            @AuthenticationPrincipal String userId,
            @PathVariable String id) {
        if (!isAdmin(userId)) return forbidden();
        feedbackRepo.deleteById(id);
        return ResponseEntity.ok(ApiResponse.ok("Deleted"));
    }

    private boolean isAdmin(String userId) {
        if (userId == null) return false;
        return userRepo.findById(userId)
            .map(u -> "ADMIN".equals(u.getRole())).orElse(false);
    }
    private ResponseEntity<?> forbidden() {
        return ResponseEntity.status(403).body(ApiResponse.error("Admin access required"));
    }
}
