package com.callverse.core.application.interfaces;

import com.callverse.core.domain.enums.Intent;
import com.callverse.core.domain.enums.ProductCategory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Persisted product, knowledge and operational configuration, separate from account access. */
public interface AdminReferenceData {
    record ProductInput(String code, String name, ProductCategory category, BigDecimal monthlyFee,
                        BigDecimal interestRate, Boolean active) {}
    record ProductView(UUID id, String code, String name, ProductCategory category, BigDecimal monthlyFee,
                       BigDecimal interestRate, boolean active, long accountCount) {}
    record ArticleInput(String category, String title, String content, List<String> tags,
                        Boolean published, Integer expectedVersion) {}
    record ArticleView(UUID id, String category, String title, String content, List<String> tags,
                       boolean published, int version, Instant updatedAt, boolean semanticIndexed) {}
    record SlaInput(String skill, Integer targetSeconds, BigDecimal targetRatio, Boolean active) {}
    record SlaView(UUID id, String skill, int targetSeconds, BigDecimal targetRatio, boolean active) {}
    record RoutingInput(String name, Intent intent, String skill, Integer priority, Boolean active) {}
    record RoutingView(UUID id, String name, Intent intent, String skill, int priority, boolean active,
                       boolean conditionsSupported) {}
    record SkillOption(String code, String label) {}
    record Options(List<SkillOption> skills, List<Intent> intents) {}

    List<ProductView> products(String q);
    ProductView saveProduct(UUID id, ProductInput input);
    void deleteProduct(UUID id);
    List<ArticleView> articles(String q);
    ArticleView saveArticle(UUID id, ArticleInput input);
    void deleteArticle(UUID id, int expectedVersion);
    List<SlaView> slaPolicies();
    SlaView saveSla(UUID id, SlaInput input);
    void deleteSla(UUID id);
    List<RoutingView> routingRules();
    RoutingView saveRouting(UUID id, RoutingInput input);
    void deleteRouting(UUID id);
    Options options();
}
