package com.callverse.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Holds the AI service's shared key and answers one question: is this the key?
 *
 * <p><strong>Constant time, over fixed-length digests.</strong> {@code String.equals} returns at the
 * first differing character, so its timing tells an attacker how many leading characters they have
 * right. {@link MessageDigest#isEqual} does not short-circuit, and comparing SHA-256 digests rather
 * than the raw strings means both inputs are always 32 bytes, so the comparison does not leak the
 * key's length either. The raw key is not kept after construction.
 *
 * <p><strong>Startup guards.</strong> {@code internal.service-key: ${INTERNAL_SERVICE_KEY}} has no
 * default, so a missing key fails placeholder resolution; this constructor also refuses a key under
 * 32 bytes. Either way the application does not start, which beats starting with {@code /internal}
 * guarded by a guessable secret.
 *
 * <p><strong>What one shared key cannot do.</strong> It identifies "the AI service", not which agent
 * or which conversation, and it carries unlimited scope over every tool. A leaked key is full tool
 * access until it is rotated. Keeping {@code /internal} off the public internet (the compose network)
 * is the mitigation; a per-agent credential would need a table and is not warranted with one caller.
 */
@Component
class ServiceKeyVerifier {

    private static final int MIN_KEY_BYTES = 32;

    private final byte[] expectedDigest;

    ServiceKeyVerifier(@Value("${internal.service-key}") String key) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_KEY_BYTES) {
            // Deliberately does not echo the value: startup logs are read by more people than secrets are.
            throw new IllegalStateException(
                    "internal.service-key must be at least %d bytes; it is %d. Generate one with: openssl rand -base64 48"
                            .formatted(MIN_KEY_BYTES, keyBytes.length));
        }
        this.expectedDigest = sha256(keyBytes);
    }

    boolean matches(String presented) {
        if (presented == null || presented.isEmpty()) {
            return false;
        }
        return MessageDigest.isEqual(sha256(presented.getBytes(StandardCharsets.UTF_8)), expectedDigest);
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every Java platform", e);
        }
    }
}
