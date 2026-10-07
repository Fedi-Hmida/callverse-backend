package com.callverse.core.application.interfaces;

/**
 * Pushes live events to connected clients. Use cases announce what happened; they never see a
 * socket, a topic name or Spring messaging, which stay in the adapter.
 *
 * <p><strong>After commit, or not at all.</strong> When called inside a database transaction, the
 * adapter holds the event until the transaction commits and drops it on rollback, so no client ever
 * hears about a change the database does not hold.
 */
public interface RealtimeEventPublisher {

    void publishSupervisionAlert(SupervisionAlert alert);

    /** To {@code /topic/queue/{event.skill}}. */
    void publishQueueEvent(QueueEvent event);

    /** To {@code /topic/conversation/{event.conversationId}}. */
    void publishConversationEvent(ConversationEvent event);

    /** To {@code /topic/supervision/kpi}. */
    void publishLiveKpi(LiveKpiSnapshot snapshot);
}
