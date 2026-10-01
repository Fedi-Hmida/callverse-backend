package com.callverse.host.api.controllers;

import com.callverse.core.application.features.card.commands.BlockCardCommand;
import com.callverse.core.application.features.card.commands.BlockCardCommandHandler;
import com.callverse.core.application.interfaces.Cards.CardRecord;
import com.callverse.core.application.interfaces.CurrentPrincipalProvider;
import com.callverse.host.api.dto.request.BlockCardRequest;
import com.callverse.host.api.dto.response.CardResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Payment-card actions taken during a call. */
@RestController
@RequestMapping(path = "/api/v1/cards", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Cards", description = "Payment-card actions")
public class CardController {

    private final BlockCardCommandHandler blockCard;
    private final CurrentPrincipalProvider principals;

    @PostMapping(path = "/{id}/block", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Roles.AGENTS)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            operationId = "blockCard",
            summary = "Block a payment card",
            description = "Blocks an ACTIVE card with a reason and the server's time. Idempotent: an already "
                    + "BLOCKED card is returned unchanged, keeping its first reason and time. An EXPIRED or "
                    + "CANCELLED card is 409 INVALID_STATE_TRANSITION; an unknown card 404. Advisors and "
                    + "supervisors only.")
    public CardResponse block(@PathVariable UUID id, @Valid @RequestBody BlockCardRequest request) {
        CardRecord card = blockCard.handle(new BlockCardCommand(id, request.reason()));
        // The schema has no actor column on card; until it does, the log is the record of who blocked.
        log.info("AUDIT card={} status={} reason={} by user={}", card.id(), card.status(), card.blockReason(), actor());
        return CardResponse.from(card);
    }

    /** The authenticated user's id for the audit line; the route guarantees one exists. */
    private String actor() {
        return principals.current().map(p -> p.userId().toString()).orElse("unknown");
    }

}
