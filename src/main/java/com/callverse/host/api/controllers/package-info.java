/**
 * REST controllers exposing /api/v1 to the Next.js frontend. The /internal tool API for the Python
 * AI service was withdrawn on 2026-09-30 pending the AI-integration phase (git tag
 * internal-tools-http-surface). They translate HTTP into application commands and queries and carry no
 * business rules; the backend, not the agent, remains the authority on rules such as the
 * commercial-credit ceiling.
 */
package com.callverse.host.api.controllers;
