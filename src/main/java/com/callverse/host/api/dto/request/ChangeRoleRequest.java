package com.callverse.host.api.dto.request;

import com.callverse.core.domain.enums.UserRole;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** Body of {@code PUT /api/v1/admin/users/{id}/role}. */
@Schema(name = "ChangeRoleRequest")
public record ChangeRoleRequest(
        @NotNull @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "SUPERVISOR") UserRole role) {}
