package com.tradevision.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Audit item P0-1, full context in LiveCanaryRecord's own class javadoc. Deliberately has no
 * quantity field -- see LiveCanaryService.startCanary's own javadoc for why that's computed by
 * this application itself, never typed in by the caller.
 */
@Data
public class StartLiveCanaryRequest {
    @NotBlank private String symbol;
    @NotBlank private String confirm;
}
