package com.callverse.core.application.features.admin;

import com.callverse.core.application.exceptions.ActionNotPermittedException;
import com.callverse.core.application.exceptions.AuthenticationRequiredException;
import com.callverse.core.application.exceptions.InvalidRequestException;
import com.callverse.core.application.interfaces.AdminReferenceData;
import com.callverse.core.application.interfaces.AdminReferenceData.*;
import com.callverse.core.application.interfaces.AuthenticatedPrincipal;
import com.callverse.core.domain.enums.UserRole;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Framework-free validation and authorization for back-office reference data. */
public class ReferenceDataAdminHandler {
    private final AdminReferenceData data;
    public ReferenceDataAdminHandler(AdminReferenceData data) { this.data = data; }

    public List<ProductView> products(AuthenticatedPrincipal caller, String q) {
        admin(caller); return data.products(query(q));
    }
    public ProductView saveProduct(AuthenticatedPrincipal caller, UUID id, ProductInput input) {
        admin(caller); require(input != null, "Product is required");
        String code = text(input.code(), 40, "code").toUpperCase(Locale.ROOT);
        require(code.matches("[A-Z0-9][A-Z0-9_-]{1,39}"), "code must contain 2 to 40 letters, digits, _ or -");
        String name = text(input.name(), 120, "name");
        require(input.category() != null && input.active() != null, "category and active are required");
        decimal(input.monthlyFee(), new BigDecimal("999999.99"), 2, "monthlyFee");
        if (input.interestRate() != null) decimal(input.interestRate(), BigDecimal.ONE, 4, "interestRate");
        return data.saveProduct(id, new ProductInput(code, name, input.category(), input.monthlyFee(),
                input.interestRate(), input.active()));
    }
    public void deleteProduct(AuthenticatedPrincipal caller, UUID id) { admin(caller); data.deleteProduct(id); }

    public List<ArticleView> articles(AuthenticatedPrincipal caller, String q) {
        admin(caller); return data.articles(query(q));
    }
    public ArticleView saveArticle(AuthenticatedPrincipal caller, UUID id, ArticleInput input) {
        admin(caller); require(input != null, "Article is required");
        String category = text(input.category(), 40, "category");
        String title = text(input.title(), 200, "title");
        String content = text(input.content(), 50000, "content");
        require(input.published() != null, "published is required");
        require(input.tags() == null || input.tags().size() <= 20, "At most 20 tags are allowed");
        List<String> tags = input.tags() == null ? List.of() : input.tags().stream()
                .map(tag -> text(tag, 60, "tag")).distinct().toList();
        if (id != null) require(input.expectedVersion() != null && input.expectedVersion() > 0,
                "expectedVersion is required for edits");
        return data.saveArticle(id, new ArticleInput(category, title, content, tags, input.published(), input.expectedVersion()));
    }
    public void deleteArticle(AuthenticatedPrincipal caller, UUID id, int version) {
        admin(caller); require(version > 0, "expectedVersion must be positive"); data.deleteArticle(id, version);
    }
    public List<SlaView> slaPolicies(AuthenticatedPrincipal caller) { admin(caller); return data.slaPolicies(); }
    public SlaView saveSla(AuthenticatedPrincipal caller, UUID id, SlaInput input) {
        admin(caller); require(input != null, "SLA policy is required");
        require(input.targetSeconds() != null && input.targetSeconds() >= 1 && input.targetSeconds() <= 86400,
                "targetSeconds must be between 1 and 86400");
        decimal(input.targetRatio(), BigDecimal.ONE, 3, "targetRatio");
        require(input.targetRatio().signum() > 0 && input.active() != null, "targetRatio must be positive and active is required");
        return data.saveSla(id, new SlaInput(skill(input.skill()), input.targetSeconds(), input.targetRatio(), input.active()));
    }
    public void deleteSla(AuthenticatedPrincipal caller, UUID id) { admin(caller); data.deleteSla(id); }
    public List<RoutingView> routingRules(AuthenticatedPrincipal caller) { admin(caller); return data.routingRules(); }
    public RoutingView saveRouting(AuthenticatedPrincipal caller, UUID id, RoutingInput input) {
        admin(caller); require(input != null, "Routing rule is required");
        require(input.priority() != null && input.priority() >= 0 && input.priority() <= 1000000,
                "priority must be between 0 and 1000000");
        require(input.active() != null, "active is required");
        String skill = skill(input.skill()); require(skill != null, "skill is required");
        return data.saveRouting(id, new RoutingInput(text(input.name(), 80, "name"), input.intent(), skill,
                input.priority(), input.active()));
    }
    public void deleteRouting(AuthenticatedPrincipal caller, UUID id) { admin(caller); data.deleteRouting(id); }
    public Options options(AuthenticatedPrincipal caller) { admin(caller); return data.options(); }

    private static String query(String value) {
        if (value == null || value.isBlank()) return "";
        require(value.strip().length() <= 120, "q must have at most 120 characters"); return value.strip();
    }
    private static String skill(String value) {
        return value == null || value.isBlank() ? null : text(value, 30, "skill").toUpperCase(Locale.ROOT);
    }
    private static String text(String value, int max, String field) {
        require(value != null && !value.isBlank() && value.strip().length() <= max,
                field + " is required and must have at most " + max + " characters"); return value.strip();
    }
    private static void decimal(BigDecimal value, BigDecimal max, int scale, String field) {
        require(value != null && value.signum() >= 0 && value.compareTo(max) <= 0
                && value.stripTrailingZeros().scale() <= scale, field + " is outside its allowed range or precision");
    }
    private static void require(boolean accepted, String message) { if (!accepted) throw new InvalidRequestException(message); }
    private static void admin(AuthenticatedPrincipal caller) {
        if (caller == null) throw new AuthenticationRequiredException();
        if (caller.role() != UserRole.ADMIN) throw new ActionNotPermittedException("Reference administration requires ADMIN.");
    }
}
