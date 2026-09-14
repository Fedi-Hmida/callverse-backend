/**
 * Input shapes for use cases that change state. These are the application's own contract and
 * stay deliberately separate from the HTTP request bodies in host.api.dto.request, so that a
 * change to the public API cannot reach inward.
 */
package com.callverse.core.application.dto.command;
