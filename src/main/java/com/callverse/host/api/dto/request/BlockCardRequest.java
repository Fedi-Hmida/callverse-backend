package com.callverse.host.api.dto.request;

import com.callverse.core.domain.enums.CardBlockReason;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** Body of {@code POST /api/v1/cards/{id}/block}. A value outside the vocabulary is a 400. */
@Schema(name = "BlockCardRequest")
public record BlockCardRequest(
        @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "STOLEN") CardBlockReason reason) {}
