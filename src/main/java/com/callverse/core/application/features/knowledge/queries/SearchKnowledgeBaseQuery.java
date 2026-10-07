package com.callverse.core.application.features.knowledge.queries;

/**
 * @param text what to look for
 * @param limit how many articles at most; null means the default
 */
public record SearchKnowledgeBaseQuery(String text, Integer limit) {}
