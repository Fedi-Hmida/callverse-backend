package com.callverse.core.application.features.auth.queries;

/**
 * Request to identify the caller of the current request.
 *
 * <p>Parameterless for the same reason as {@code GetHealthStatusQuery}: every query in this codebase
 * is addressed by a query object, so the handler can gain an input later without changing callers.
 */
public record GetCurrentUserQuery() {}
