package com.callverse.host.api.controllers;

import com.callverse.core.application.interfaces.AccessibleConversationDirectory;
import com.callverse.core.application.interfaces.CurrentPrincipalProvider;
import com.callverse.core.application.exceptions.AuthenticationRequiredException;
import com.callverse.core.domain.enums.ConversationStatus;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/conversations")
class ConversationDirectoryController {
    private final AccessibleConversationDirectory directory;
    private final CurrentPrincipalProvider principals;

    @GetMapping("/accessible")
    @PreAuthorize(Roles.ANYONE)
    @Operation(operationId = "listAccessibleConversations", summary = "Browse conversations authorized for the signed-in role")
    public AccessibleConversationDirectory.Page list(@RequestParam(defaultValue = "") String q,
            @RequestParam(required = false) ConversationStatus status, @RequestParam(defaultValue = "0") int page) {
        return directory.search(principals.current().orElseThrow(AuthenticationRequiredException::new), q, status, page);
    }
}
