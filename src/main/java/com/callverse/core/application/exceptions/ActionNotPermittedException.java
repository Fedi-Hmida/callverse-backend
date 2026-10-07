package com.callverse.core.application.exceptions;

/**
 * The caller may see this resource but may not do this to it — an advisor resolving a conversation
 * a supervisor now owns, a supervisor writing in an advisor's chat.
 *
 * <p>Maps to <strong>403 {@code ACCESS_DENIED}</strong>, the same status, code and message as a
 * role refused by {@code @PreAuthorize}, so a client has one denial to handle. A resource the caller
 * may not even see is answered with 404 instead ({@link ResourceNotFoundException}), so that its
 * existence does not leak.
 */
public class ActionNotPermittedException extends ApplicationException {

    private static final String CODE = "ACCESS_DENIED";

    public ActionNotPermittedException(String reason) {
        super(CODE, reason);
    }
}
