package com.callverse.host.api.controllers;

/**
 * The role expressions every controller uses, named once so that a route's audience is readable at
 * a glance and a change of policy is one edit, not a search.
 *
 * <p><strong>STAFF reads any customer, by decision.</strong> Until schema change S-1 makes "the
 * customer whose user is me" and "the conversations assigned to me" expressible, an advisor can
 * read every customer through these routes. That is a known, documented gap, closed by the
 * ownership phase — not an oversight.
 */
final class Roles {

    /** Advisors, supervisors and administrators: everyone who works inside the bank. */
    static final String STAFF = "hasAnyRole('ADVISOR','SUPERVISOR','ADMIN')";

    /** The people who act on a customer's behalf during a call. ADMIN administers; it does not serve. */
    static final String AGENTS = "hasAnyRole('ADVISOR','SUPERVISOR')";

    /**
     * Advisors only. Used where the stored record names the actor's kind: an escalation is raised
     * by an ADVISOR (raised_by has no SUPERVISOR value), and a supervisor is who it goes to.
     */
    static final String ADVISOR = "hasRole('ADVISOR')";

    /** The two parties to a conversation: its advisor and its customer. Supervisors read, they do not write. */
    static final String PARTIES = "hasAnyRole('ADVISOR','CUSTOMER')";

    /** Who watches the floor: supervisors, and administrators who run the platform. */
    static final String SUPERVISION = "hasAnyRole('SUPERVISOR','ADMIN')";

    /** Anyone signed in, customers included. */
    static final String ANYONE = "isAuthenticated()";

    private Roles() {}
}
