package com.callverse.core.application.interfaces;

import com.callverse.core.application.interfaces.QualityAgent.SourceDocument;
import java.util.List;
import java.util.UUID;

/**
 * The knowledge-base articles the AI Advisor cited in a conversation (its private {@code sources}
 * trace), so the Quality Analyst can check the advisor's claims against what was actually used.
 */
public interface CitedKnowledge {

    /** @return each cited, still existing article once, in no particular order; empty when none */
    List<SourceDocument> citedInAiTurns(UUID conversationId);
}
