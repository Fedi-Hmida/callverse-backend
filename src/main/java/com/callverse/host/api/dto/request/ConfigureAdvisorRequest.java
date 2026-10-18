package com.callverse.host.api.dto.request;

import com.callverse.core.domain.enums.AdvisorStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

public record ConfigureAdvisorRequest(
        @NotNull AdvisorStatus status,
        @Min(1) @Max(10) int maxConcurrent,
        @NotNull @Size(max = 50) Map<@Pattern(regexp = "[A-Z][A-Z0-9_]{0,29}") String,
                @NotNull @Min(1) @Max(3) Integer> skills) {}
