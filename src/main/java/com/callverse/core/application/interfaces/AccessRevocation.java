package com.callverse.core.application.interfaces;

import java.util.UUID;

/**
 * Cuts the live sessions of an account whose access was just withdrawn or changed.
 *
 * <p>REST needs nothing more: every request re-reads the account. A WebSocket opened earlier would
 * keep receiving, so it is cut here: no more frames, no new subscriptions, until the user logs in
 * again with what they are now allowed.
 */
public interface AccessRevocation {

    void revoke(UUID userId);
}
