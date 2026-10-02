package com.callverse.host.api.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/v1/conversations/{id}/messages}. The text only: who sent it is decided by
 * the server from the caller, and any {@code sender} field in the body is ignored.
 */
@Schema(name = "MessageRequest")
public record MessageRequest(
        @NotBlank @Size(max = 2000)
                @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "Je bloque votre carte tout de suite.")
                String content) {}
