-- Bound the staff explanation lookup to the decision for this exact AI message.
CREATE INDEX idx_advisor_decision_message
    ON agent_decision ((observation->>'conversationId'), (observation->>'messageId'), created_at DESC)
    WHERE agent_type = 'CUSTOMER_ADVISOR';
