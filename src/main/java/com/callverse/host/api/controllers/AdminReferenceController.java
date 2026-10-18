package com.callverse.host.api.controllers;

import com.callverse.core.application.exceptions.AuthenticationRequiredException;
import com.callverse.core.application.features.admin.ReferenceDataAdminHandler;
import com.callverse.core.application.interfaces.AdminReferenceData.*;
import com.callverse.core.application.interfaces.AuthenticatedPrincipal;
import com.callverse.core.application.interfaces.CurrentPrincipalProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** All writes are validated by the application handler and persisted transactionally. */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@PreAuthorize(Roles.ADMIN)
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Reference administration")
public class AdminReferenceController {
    private final ReferenceDataAdminHandler handler;
    private final CurrentPrincipalProvider principals;

    @GetMapping("/products")
    @Operation(operationId = "adminProducts", summary = "Search products, up to 200 matches")
    public List<ProductView> products(@RequestParam(defaultValue = "") String q) { return handler.products(caller(), q); }
    @PostMapping("/products")
    @Operation(operationId = "createAdminProduct", summary = "Create a banking product")
    public ResponseEntity<ProductView> createProduct(@RequestBody ProductInput input) {
        ProductView created = handler.saveProduct(caller(), null, input);
        return ResponseEntity.created(URI.create("/api/v1/admin/products/" + created.id())).body(created);
    }
    @PutMapping("/products/{id}")
    @Operation(operationId = "editAdminProduct", summary = "Edit a banking product")
    public ProductView editProduct(@PathVariable UUID id, @RequestBody ProductInput input) { return handler.saveProduct(caller(), id, input); }
    @DeleteMapping("/products/{id}")
    @Operation(operationId = "deleteAdminProduct", summary = "Delete an unused banking product")
    public ResponseEntity<Void> deleteProduct(@PathVariable UUID id) { handler.deleteProduct(caller(), id); return ResponseEntity.noContent().build(); }

    @GetMapping("/knowledge-articles")
    @Operation(operationId = "adminKnowledgeArticles", summary = "Search published articles and drafts, up to 100 matches")
    public List<ArticleView> articles(@RequestParam(defaultValue = "") String q) { return handler.articles(caller(), q); }
    @PostMapping("/knowledge-articles")
    @Operation(operationId = "createAdminKnowledgeArticle", summary = "Create a knowledge article")
    public ResponseEntity<ArticleView> createArticle(@RequestBody ArticleInput input) {
        ArticleView created = handler.saveArticle(caller(), null, input);
        return ResponseEntity.created(URI.create("/api/v1/admin/knowledge-articles/" + created.id())).body(created);
    }
    @PutMapping("/knowledge-articles/{id}")
    @Operation(operationId = "editAdminKnowledgeArticle", summary = "Edit a versioned knowledge article")
    public ArticleView editArticle(@PathVariable UUID id, @RequestBody ArticleInput input) { return handler.saveArticle(caller(), id, input); }
    @DeleteMapping("/knowledge-articles/{id}")
    @Operation(operationId = "deleteAdminKnowledgeArticle", summary = "Delete a versioned knowledge article")
    public ResponseEntity<Void> deleteArticle(@PathVariable UUID id, @RequestParam int expectedVersion) {
        handler.deleteArticle(caller(), id, expectedVersion); return ResponseEntity.noContent().build();
    }

    @GetMapping("/sla-policies")
    @Operation(operationId = "adminSlaPolicies", summary = "List SLA policies")
    public List<SlaView> slaPolicies() { return handler.slaPolicies(caller()); }
    @PostMapping("/sla-policies")
    @Operation(operationId = "createAdminSlaPolicy", summary = "Create an SLA policy")
    public ResponseEntity<SlaView> createSla(@RequestBody SlaInput input) {
        SlaView created = handler.saveSla(caller(), null, input);
        return ResponseEntity.created(URI.create("/api/v1/admin/sla-policies/" + created.id())).body(created);
    }
    @PutMapping("/sla-policies/{id}")
    @Operation(operationId = "editAdminSlaPolicy", summary = "Edit an SLA policy while preserving coverage")
    public SlaView editSla(@PathVariable UUID id, @RequestBody SlaInput input) { return handler.saveSla(caller(), id, input); }
    @DeleteMapping("/sla-policies/{id}")
    @Operation(operationId = "deleteAdminSlaPolicy", summary = "Delete an SLA policy while preserving coverage")
    public ResponseEntity<Void> deleteSla(@PathVariable UUID id) { handler.deleteSla(caller(), id); return ResponseEntity.noContent().build(); }

    @GetMapping("/routing-rules/options")
    @Operation(operationId = "adminRoutingOptions", summary = "List routing skills and intents")
    public Options options() { return handler.options(caller()); }
    @GetMapping("/routing-rules")
    @Operation(operationId = "adminRoutingRules", summary = "List routing rules")
    public List<RoutingView> routingRules() { return handler.routingRules(caller()); }
    @PostMapping("/routing-rules")
    @Operation(operationId = "createAdminRoutingRule", summary = "Create an intent routing rule")
    public ResponseEntity<RoutingView> createRouting(@RequestBody RoutingInput input) {
        RoutingView created = handler.saveRouting(caller(), null, input);
        return ResponseEntity.created(URI.create("/api/v1/admin/routing-rules/" + created.id())).body(created);
    }
    @PutMapping("/routing-rules/{id}")
    @Operation(operationId = "editAdminRoutingRule", summary = "Edit an intent routing rule")
    public RoutingView editRouting(@PathVariable UUID id, @RequestBody RoutingInput input) { return handler.saveRouting(caller(), id, input); }
    @DeleteMapping("/routing-rules/{id}")
    @Operation(operationId = "deleteAdminRoutingRule", summary = "Delete an intent routing rule")
    public ResponseEntity<Void> deleteRouting(@PathVariable UUID id) { handler.deleteRouting(caller(), id); return ResponseEntity.noContent().build(); }

    private AuthenticatedPrincipal caller() { return principals.current().orElseThrow(AuthenticationRequiredException::new); }
}
