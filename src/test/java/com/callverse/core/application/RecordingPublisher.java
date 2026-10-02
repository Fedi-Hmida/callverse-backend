package com.callverse.core.application;

import com.callverse.core.application.interfaces.ConversationEvent;
import com.callverse.core.application.interfaces.LiveKpiSnapshot;
import com.callverse.core.application.interfaces.QueueEvent;
import com.callverse.core.application.interfaces.RealtimeEventPublisher;
import com.callverse.core.application.interfaces.SupervisionAlert;
import java.util.ArrayList;
import java.util.List;

/** Records every event a use case announces, in order, for handler tests. */
public class RecordingPublisher implements RealtimeEventPublisher {

    public final List<SupervisionAlert> alerts = new ArrayList<>();
    public final List<QueueEvent> queueEvents = new ArrayList<>();
    public final List<ConversationEvent> conversationEvents = new ArrayList<>();
    public final List<LiveKpiSnapshot> kpis = new ArrayList<>();

    @Override
    public void publishSupervisionAlert(SupervisionAlert alert) {
        alerts.add(alert);
    }

    @Override
    public void publishQueueEvent(QueueEvent event) {
        queueEvents.add(event);
    }

    @Override
    public void publishConversationEvent(ConversationEvent event) {
        conversationEvents.add(event);
    }

    @Override
    public void publishLiveKpi(LiveKpiSnapshot snapshot) {
        kpis.add(snapshot);
    }

    public boolean isEmpty() {
        return alerts.isEmpty() && queueEvents.isEmpty() && conversationEvents.isEmpty() && kpis.isEmpty();
    }
}
