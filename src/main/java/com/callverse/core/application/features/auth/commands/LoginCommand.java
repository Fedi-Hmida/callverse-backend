package com.callverse.core.application.features.auth.commands;

/**
 * A request to exchange credentials for an access token.
 *
 * @param email the submitted address, matched case-insensitively
 * @param rawPassword the submitted password. Never log this, never put it in an exception message,
 *     and never echo it back in a response.
 */
public record LoginCommand(String email, String rawPassword) {}
