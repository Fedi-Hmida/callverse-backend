package com.callverse.host.api.controllers;

import com.callverse.core.application.features.knowledge.queries.SearchKnowledgeBaseQuery;
import com.callverse.core.application.features.knowledge.queries.SearchKnowledgeBaseQueryHandler;
import com.callverse.host.api.dto.response.KnowledgeArticlesResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Searches the bank's internal procedures. Staff only: drafts and procedures are not public. */
@RestController
@RequestMapping(path = "/api/v1/kb/articles", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "Knowledge base", description = "Internal procedures for staff")
public class KnowledgeBaseController {

    private final SearchKnowledgeBaseQueryHandler searchKnowledgeBase;

    @GetMapping
    @PreAuthorize(Roles.STAFF)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(
            operationId = "searchKnowledgeArticles",
            summary = "Search published articles",
            description = "Case-insensitive search over published articles; q is 2 to 100 characters, "
                    + "limit 1 to 10 (default 5). Staff only.")
    public KnowledgeArticlesResponse search(
            @RequestParam String q, @RequestParam(required = false) Integer limit) {
        return KnowledgeArticlesResponse.of(q.trim(), searchKnowledgeBase.handle(new SearchKnowledgeBaseQuery(q, limit)));
    }
}
