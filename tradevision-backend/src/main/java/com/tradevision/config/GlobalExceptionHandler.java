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
     * Review finding (P1 — "Global exception handling leaks internal messages"): confirmed real.
     * This is the catch-all for genuinely UNEXPECTED exceptions — MongoDB connection errors,
     * NullPointerExceptions, IO failures, Binance/broker API error bodies — none of which are
     * hand-authored, safe, user-facing text the way IllegalArgumentException's messages are
     * throughout this codebase (deliberately left alone below; those ARE meant to be shown).
     * ex.getMessage() here could contain internal hostnames, config details, or raw upstream
     * error bodies. Logged in full server-side with a reference id instead of exposed to the
     * client — support can correlate a user's report against the log using that id without this
     * response ever having to carry anything internal.
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
        // Same reasoning as IllegalArgumentException: used throughout this codebase (resume
        // validation, position safety) as a deliberate, hand-authored, safe user-facing message
        // — not a leak of anything internal.
        //
        // P2-16 fix ("GlobalExceptionHandler.handleBadState returns IllegalStateException
        // messages to clients" -- external review): the assumption above was NOT actually true
        // everywhere until this fix -- several IllegalStateException throw sites across this
        // codebase (BinanceBrokerAdapter's market-data/listen-key failures, CredentialEncryption
        // Service's key-resolution failures) were embedding e.getMessage(), a raw upstream
        // response body, or an internal config/keyId detail directly into the message this
        // handler returns verbatim. Every one of those has now been audited and fixed at its own
        // throw site (full context in BinanceBrokerAdapter.getSymbolRules' own updated comment
        // and CredentialEncryptionService.decrypt's own updated comment) rather than here --
        // fixing it here alone (e.g. by no longer returning ex.getMessage() at all) would have
        // broken the many genuinely-safe, hand-authored business messages (resume validation,
        // credential deletion/rotation guards) that this handler exists to surface intact.
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
