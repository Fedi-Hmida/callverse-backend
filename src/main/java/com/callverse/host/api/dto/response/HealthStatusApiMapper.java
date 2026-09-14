package com.callverse.host.api.dto.response;

import com.callverse.core.application.features.health.queries.HealthStatusResult;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

/**
 * Maps the health use case's result onto the API response body.
 *
 * <p>MapStruct generates the implementation at compile time, so the mapping is checked by the
 * compiler rather than by a test. The build additionally sets
 * {@code -Amapstruct.unmappedTargetPolicy=ERROR}, which means a response field this mapper cannot
 * populate fails the build instead of silently serialising as {@code null}.
 *
 * <p>The {@code ServiceStatus} to {@code String} conversion is MapStruct's built-in enum handling;
 * it is spelled out nowhere because writing it by hand is what the processor exists to avoid.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface HealthStatusApiMapper {

    HealthStatusResponse toResponse(HealthStatusResult result);
}
