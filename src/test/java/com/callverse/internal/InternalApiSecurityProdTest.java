package com.callverse.internal;

import org.springframework.test.context.ActiveProfiles;

/** {@code /internal} under the deny-by-default chain: same scheme, same answers as under {@code dev}. */
@ActiveProfiles("prod")
class InternalApiSecurityProdTest extends AbstractInternalApiSecurityTest {}
