package com.callverse.infrastructure.persistence;

import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.interfaces.AdminReferenceData;
import com.callverse.core.domain.entities.*;
import com.callverse.core.domain.enums.Intent;
import com.callverse.core.domain.exceptions.ReferenceDataConflictException;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Existing schema only. All catalog writes share a transaction-scoped database lock. */
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
class AdminReferenceDataAdapter implements AdminReferenceData {
    private final EntityManager em;

    @Override
    public List<ProductView> products(String q) {
        return em.createQuery("select p from BankingProduct p where lower(p.code) like :q escape '!' "
                        + "or lower(p.name) like :q escape '!' order by p.code", BankingProduct.class)
                .setParameter("q", KnowledgeBaseAdapter.containsPattern(q)).setMaxResults(200).getResultList()
                .stream().map(this::productView).toList();
    }

    @Override @Transactional
    public ProductView saveProduct(UUID id, ProductInput input) {
        lockWrites();
        BankingProduct product = id == null ? new BankingProduct() : find(BankingProduct.class, id, "Product");
        if (id != null && !product.getCode().equals(input.code())) conflict("Le code produit est permanent ; créez un nouveau produit.");
        long duplicates = em.createQuery("select count(p) from BankingProduct p where p.code = :code "
                        + "and (:id is null or p.id <> :id)", Long.class)
                .setParameter("code", input.code()).setParameter("id", id).getSingleResult();
        if (duplicates > 0) conflict("Ce code produit est déjà utilisé.");
        if (id != null && product.getCategory() != input.category() && accounts(id) > 0)
            conflict("La catégorie d’un produit utilisé par un compte ne peut pas changer.");
        product.setCode(input.code()); product.setName(input.name()); product.setCategory(input.category());
        product.setMonthlyFee(input.monthlyFee()); product.setInterestRate(input.interestRate()); product.setActive(input.active());
        if (id == null) em.persist(product);
        em.flush(); return productView(product);
    }

    @Override @Transactional
    public void deleteProduct(UUID id) {
        lockWrites(); BankingProduct product = find(BankingProduct.class, id, "Product");
        if (accounts(id) > 0) conflict("Ce produit est utilisé par des comptes. Retirez-le du catalogue en le désactivant.");
        em.remove(product); em.flush();
    }

    @Override
    public List<ArticleView> articles(String q) {
        return em.createQuery("select a from KbArticle a where lower(a.title) like :q escape '!' "
                        + "or lower(a.content) like :q escape '!' order by a.updatedAt desc, a.id", KbArticle.class)
                .setParameter("q", KnowledgeBaseAdapter.containsPattern(q)).setMaxResults(100).getResultList()
                .stream().map(this::articleView).toList();
    }

    @Override @Transactional
    public ArticleView saveArticle(UUID id, ArticleInput input) {
        lockWrites(); KbArticle article = id == null ? new KbArticle() : find(KbArticle.class, id, "Article");
        if (id != null && article.getVersion() != input.expectedVersion()) conflict("Cet article a changé. Actualisez-le avant de modifier.");
        boolean changed = id == null || !article.getTitle().equals(input.title()) || !article.getContent().equals(input.content())
                || !article.getCategory().equals(input.category()) || article.isPublished() != input.published()
                || !Arrays.equals(article.getTags(), input.tags().toArray(String[]::new));
        if (id != null && changed) {
            // A vector for an older text must never answer a customer after this commit.
            em.createNativeQuery("delete from kb_chunk where article_id = :id").setParameter("id", id).executeUpdate();
            article.setVersion(Math.addExact(article.getVersion(), 1));
        }
        article.setCategory(input.category()); article.setTitle(input.title()); article.setContent(input.content());
        article.setTags(input.tags().toArray(String[]::new)); article.setPublished(input.published());
        if (id == null) em.persist(article);
        em.flush(); return articleView(article);
    }

    @Override @Transactional
    public void deleteArticle(UUID id, int expectedVersion) {
        lockWrites(); KbArticle article = find(KbArticle.class, id, "Article");
        if (article.getVersion() != expectedVersion) conflict("Cet article a changé. Actualisez-le avant de supprimer.");
        em.remove(article); em.flush();
    }

    @Override
    public List<SlaView> slaPolicies() {
        return em.createQuery("select p from SlaPolicy p left join fetch p.skill order by p.targetSeconds, p.id", SlaPolicy.class)
                .getResultList().stream().map(this::slaView).toList();
    }

    @Override @Transactional
    public SlaView saveSla(UUID id, SlaInput input) {
        lockWrites(); SlaPolicy policy = id == null ? new SlaPolicy() : find(SlaPolicy.class, id, "SLA policy");
        boolean protectedCoverage = id != null && policy.isActive();
        UUID previousSkill = policy.getSkill() == null ? null : policy.getSkill().getId();
        Skill skill = input.skill() == null ? null : skill(input.skill());
        if (input.active()) {
            long duplicate = em.createQuery("select count(p) from SlaPolicy p where p.active = true "
                            + "and ((:skill is null and p.skill is null) or p.skill.id = :skill) and (:id is null or p.id <> :id)", Long.class)
                    .setParameter("skill", skill == null ? null : skill.getId()).setParameter("id", id).getSingleResult();
            if (duplicate > 0) conflict("Une politique active existe déjà pour cette compétence. Modifiez-la directement.");
        }
        policy.setSkill(skill); policy.setTargetSeconds(input.targetSeconds()); policy.setTargetRatio(input.targetRatio());
        policy.setActive(input.active()); if (id == null) em.persist(policy);
        em.flush(); if (protectedCoverage) requireSlaCoverage(previousSkill); return slaView(policy);
    }

    @Override @Transactional
    public void deleteSla(UUID id) {
        lockWrites(); SlaPolicy policy = find(SlaPolicy.class, id, "SLA policy");
        boolean protectedCoverage = policy.isActive();
        UUID previousSkill = policy.getSkill() == null ? null : policy.getSkill().getId();
        em.remove(policy); em.flush(); if (protectedCoverage) requireSlaCoverage(previousSkill);
    }

    @Override
    public List<RoutingView> routingRules() {
        return em.createQuery("select r from RoutingRule r left join fetch r.skill order by r.priority, r.id", RoutingRule.class)
                .getResultList().stream().map(this::routingView).toList();
    }

    @Override @Transactional
    public RoutingView saveRouting(UUID id, RoutingInput input) {
        lockWrites(); RoutingRule rule = id == null ? new RoutingRule() : find(RoutingRule.class, id, "Routing rule");
        if (id != null && !supported(rule)) conflict("Cette règle contient des conditions avancées non prises en charge par cet éditeur.");
        rule.setName(input.name()); rule.setIntent(input.intent()); rule.setSkill(skill(input.skill()));
        rule.setPriority(input.priority()); rule.setActive(input.active()); rule.setConditions(JsonNodeFactory.instance.objectNode());
        if (id == null) em.persist(rule); em.flush(); return routingView(rule);
    }

    @Override @Transactional
    public void deleteRouting(UUID id) { lockWrites(); em.remove(find(RoutingRule.class, id, "Routing rule")); em.flush(); }

    @Override
    public Options options() {
        return new Options(em.createQuery("select s from Skill s order by s.code", Skill.class).getResultList().stream()
                .map(s -> new SkillOption(s.getCode(), s.getLabel())).toList(), List.of(Intent.values()));
    }

    private void lockWrites() {
        // Serializes uniqueness/coverage checks across application instances without changing schema.
        em.createNativeQuery("select 1 from pg_advisory_xact_lock(721904243)", Integer.class).getSingleResult();
    }
    private <T> T find(Class<T> type, UUID id, String resource) {
        T entity = em.find(type, id, LockModeType.PESSIMISTIC_WRITE);
        if (entity == null) throw new ResourceNotFoundException(resource, id); return entity;
    }
    private Skill skill(String code) {
        return em.createQuery("select s from Skill s where s.code = :code", Skill.class).setParameter("code", code)
                .getResultStream().findFirst().orElseThrow(() -> new ResourceNotFoundException("Skill", code));
    }
    private long accounts(UUID id) {
        return em.createQuery("select count(a) from Account a where a.product.id = :id", Long.class)
                .setParameter("id", id).getSingleResult();
    }
    private ProductView productView(BankingProduct p) {
        return new ProductView(p.getId(), p.getCode(), p.getName(), p.getCategory(), p.getMonthlyFee(), p.getInterestRate(),
                p.isActive(), accounts(p.getId()));
    }
    private ArticleView articleView(KbArticle a) {
        long chunks = em.createQuery("select count(c) from KbChunk c where c.article.id = :id", Long.class)
                .setParameter("id", a.getId()).getSingleResult();
        return new ArticleView(a.getId(), a.getCategory(), a.getTitle(), a.getContent(),
                a.getTags() == null ? List.of() : List.of(a.getTags()), a.isPublished(), a.getVersion(), a.getUpdatedAt(), chunks > 0);
    }
    private SlaView slaView(SlaPolicy p) { return new SlaView(p.getId(), p.getSkill() == null ? null : p.getSkill().getCode(),
            p.getTargetSeconds(), p.getTargetRatio(), p.isActive()); }
    private RoutingView routingView(RoutingRule r) { return new RoutingView(r.getId(), r.getName(), r.getIntent(),
            r.getSkill() == null ? null : r.getSkill().getCode(), r.getPriority(), r.isActive(), supported(r)); }
    private static boolean supported(RoutingRule rule) {
        return rule.getConditions() == null || rule.getConditions().isNull()
                || (rule.getConditions().isObject() && rule.getConditions().isEmpty());
    }
    private void requireSlaCoverage(UUID affectedSkill) {
        boolean global = em.createQuery("select count(p) from SlaPolicy p where p.active = true and p.skill is null", Long.class)
                .getSingleResult() > 0;
        if (global) return;
        if (affectedSkill != null) {
            long covering = em.createQuery("select count(p) from SlaPolicy p where p.active = true and p.skill.id = :skill", Long.class)
                    .setParameter("skill", affectedSkill).getSingleResult();
            if (covering == 0) conflict("Cette compétence doit conserver une politique SLA active ou une politique globale.");
            return;
        }
        long missing = em.createQuery("select count(s) from Skill s where not exists "
                + "(select p.id from SlaPolicy p where p.active = true and p.skill = s)", Long.class).getSingleResult();
        if (missing > 0) conflict("Chaque compétence doit conserver une politique SLA active ou une politique globale.");
    }
    private static void conflict(String message) { throw new ReferenceDataConflictException(message); }
}
