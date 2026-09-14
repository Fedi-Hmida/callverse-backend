package com.callverse.core.application.features.health.queries;

/**
 * Request to read the platform's current status.
 *
 * <p>It carries no parameters today, which makes it look like pure overhead. It exists anyway
 * because every query in this codebase is addressed by a query object, and a handler whose input
 * is a type rather than a bare method call can gain a parameter later without changing its
 * signature or its callers.
 */
public record GetHealthStatusQuery() {}
