/**
 * REST controllers exposing /api/v1 to the Angular frontend and the /internal tool API to the
 * Python AI service. They translate HTTP into application commands and queries and carry no
 * business rules; the backend, not the agent, remains the authority on rules such as the
 * commercial-credit ceiling.
 */
package com.callverse.host.api.controllers;
