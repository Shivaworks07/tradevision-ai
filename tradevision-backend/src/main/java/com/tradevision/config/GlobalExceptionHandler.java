package com.tradevision.config;

import com.tradevision.dto.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.WebRequest;

import java.util.UUID;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Catch-all for unexpected exceptions — MongoDB connection errors, NullPointerExceptions, IO
     * failures, Binance/broker API error bodies — none of which are safe, user-facing text the
     * way IllegalArgumentException's hand-authored messages are (handled separately below).
     * ex.getMessage() could contain internal hostnames, config details, or raw upstream error
     * bodies, so it is logged in full server-side with a reference id rather than exposed to the
     * client; support can correlate a user's report against the log using that id.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<?>> handleAll(Exception ex, WebRequest req) {
        String refId = "TV-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        log.error("Unhandled exception [{}] on {}: {}", refId, req.getDescription(false), ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error("Internal server error. Reference ID: " + refId));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<?>> handleBadInput(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiResponse.error("Bad request: " + ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<?>> handleBadState(IllegalStateException ex) {
        // IllegalStateException messages are returned to the client verbatim, so every throw
        // site using this exception type (resume validation, position safety, credential
        // deletion/rotation guards, broker adapter failures) must only ever carry a
        // hand-authored, safe, user-facing message — never a raw upstream response body, internal
        // config detail, or e.getMessage() from a lower-level exception. That invariant is
        // enforced at each throw site rather than here, since stripping ex.getMessage() here
        // would also break the many genuinely safe business messages this handler exists to
        // surface intact.
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiResponse.error(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<?>> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
            .map(e -> e.getField() + ": " + e.getDefaultMessage())
            .findFirst().orElse("Validation failed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(msg));
    }
}
