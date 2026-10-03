package com.callverse.core.application.features.admin.queries;

import com.callverse.core.domain.enums.UserRole;

/**
 * @param role, active, query optional filters (null = any)
 * @param page zero-based
 * @param size 1 to 100
 */
public record ListUsersQuery(UserRole role, Boolean active, String query, int page, int size) {}
