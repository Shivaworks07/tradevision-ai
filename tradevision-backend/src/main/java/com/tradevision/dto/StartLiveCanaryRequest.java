package com.tradevision.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Request to place a minimal real-money LIVE probe order on a symbol, to validate a credential
 * end-to-end. Deliberately has no quantity field -- the order size is computed by
 * LiveCanaryService itself, never typed in by the caller, so this can't be used to place an
 * order of arbitrary size.
 */
@Data
public class StartLiveCanaryRequest {
    @NotBlank private String symbol;
    @NotBlank private String confirm;
}
