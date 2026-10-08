package com.tradevision.controller;

import com.tradevision.model.Feedback;
import com.tradevision.repository.FeedbackRepository;
import com.tradevision.repository.UserRepository;
import com.tradevision.service.DistributedRateLimitService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies per-IP rate limiting on the public, CSRF-exempt feedback submission endpoint, which
 * otherwise could store an unbounded number of near-2.8 MB base64 documents per request.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FeedbackControllerTest {

    @Mock FeedbackRepository feedbackRepo;
    @Mock UserRepository userRepo;
    @Mock DistributedRateLimitService distributedRateLimitService;
    @Mock HttpServletRequest request;

    @InjectMocks FeedbackController controller;

    @BeforeEach
    void setup() {
        // buildProperties is a java.util.Optional<BuildProperties> field, not a mockable bean
        // type @InjectMocks can wire on its own -- set directly, matching how this codebase's
        // other tests handle @Value/plain-field dependencies @InjectMocks can't infer.
        ReflectionTestUtils.setField(controller, "buildProperties", Optional.empty());
    }

    private Feedback validFeedback() {
        Feedback f = new Feedback();
        f.setTitle("Something is broken");
        f.setDescription("It broke when I clicked the button.");
        return f;
    }

    @Test
    @DisplayName("submit: within the per-IP rate limit, proceeds normally and saves the feedback")
    void submit_withinRateLimit_savesNormally() {
        when(distributedRateLimitService.allow(eq("feedback_submit_by_ip"), any(), anyInt(), anyLong())).thenReturn(true);
        when(request.getRemoteAddr()).thenReturn("1.2.3.4");
        when(feedbackRepo.save(any())).thenAnswer(inv -> {
            Feedback f = inv.getArgument(0);
            f.setId("fb1");
            return f;
        });

        var response = controller.submit(null, validFeedback(), request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(feedbackRepo).save(any());
    }

    /**
     * Simulates a burst of 100 rapid submissions from the same IP -- the first
     * MAX_FEEDBACK_SUBMISSIONS_PER_IP_PER_WINDOW (30) are allowed by the (mocked) rate limiter,
     * and every one after that is rejected with 429 and never reaches feedbackRepo.save at all,
     * proving the storage-cost DoS this endpoint is exposed to (an unbounded number of
     * near-2.8MB documents per second, with zero authentication) is bounded.
     */
    @Test
    @DisplayName("submit: a 100-request/minute burst from the same IP is rate-limited -- requests beyond the per-IP limit get 429 and are never persisted")
    void submit_hundredRequestBurst_rateLimitedBeyondThreshold() {
        when(request.getRemoteAddr()).thenReturn("203.0.113.50");
        int limit = 30; // FeedbackController.MAX_FEEDBACK_SUBMISSIONS_PER_IP_PER_WINDOW
        int[] callCount = {0};
        when(distributedRateLimitService.allow(eq("feedback_submit_by_ip"), eq("203.0.113.50"), anyInt(), anyLong()))
            .thenAnswer(inv -> ++callCount[0] <= limit);
        when(feedbackRepo.save(any())).thenAnswer(inv -> {
            Feedback f = inv.getArgument(0);
            f.setId("fb-" + System.nanoTime());
            return f;
        });

        int allowedCount = 0;
        int rejectedCount = 0;
        for (int i = 0; i < 100; i++) {
            var response = controller.submit(null, validFeedback(), request);
            if (response.getStatusCode().value() == 200) allowedCount++;
            else if (response.getStatusCode().value() == 429) rejectedCount++;
        }

        assertThat(allowedCount).isEqualTo(limit);
        assertThat(rejectedCount).isEqualTo(100 - limit);
        verify(feedbackRepo, org.mockito.Mockito.times(limit)).save(any());
    }

    @Test
    @DisplayName("submit: once the per-IP rate limit is exceeded, returns 429 and never even reaches feedbackRepo.save")
    void submit_rateLimitExceeded_returns429WithoutSaving() {
        when(distributedRateLimitService.allow(eq("feedback_submit_by_ip"), any(), anyInt(), anyLong())).thenReturn(false);
        when(request.getRemoteAddr()).thenReturn("1.2.3.4");

        var response = controller.submit(null, validFeedback(), request);

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        verify(feedbackRepo, never()).save(any());
    }

    @Test
    @DisplayName("submit: rate limiting applies even to an anonymous (unauthenticated) submitter -- this endpoint is genuinely public")
    void submit_anonymousSubmitter_stillRateLimited() {
        when(distributedRateLimitService.allow(eq("feedback_submit_by_ip"), any(), anyInt(), anyLong())).thenReturn(false);
        when(request.getRemoteAddr()).thenReturn("1.2.3.4");

        var response = controller.submit(null, validFeedback(), request);

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        verify(userRepo, never()).findById(any());
    }
}
