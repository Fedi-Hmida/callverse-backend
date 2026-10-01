package com.callverse.infrastructure.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.callverse.core.application.interfaces.SupervisionAlert;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.messaging.Message;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.AbstractMessageChannel;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * An event announces something the database now holds. Inside a transaction it leaves only after
 * the commit, and a rollback discards it: a supervisor must never see an alert for an escalation
 * that does not exist.
 */
class AfterCommitPublisherTest {

    /** Records what reaches the broker channel. */
    static final class RecordingChannel extends AbstractMessageChannel {
        final List<Message<?>> sent = new ArrayList<>();

        @Override
        protected boolean sendInternal(Message<?> message, long timeout) {
            sent.add(message);
            return true;
        }
    }

    private final RecordingChannel channel = new RecordingChannel();
    private final StompRealtimeEventPublisher publisher = new StompRealtimeEventPublisher(templateFor(channel));

    private static MappingJackson2MessageConverter template() {
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(new ObjectMapper().registerModule(new JavaTimeModule()));
        return converter;
    }

    private static SimpMessagingTemplate templateFor(RecordingChannel channel) {
        // Any converter that can write java.time; the wire format itself is pinned against the real
        // broker converter in StompHardeningTest.
        SimpMessagingTemplate template = new SimpMessagingTemplate(channel);
        template.setMessageConverter(template());
        return template;
    }

    private static SupervisionAlert alert() {
        return SupervisionAlert.escalationRaised(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Instant.now());
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("outside a transaction the alert is sent at once, to the supervision topic")
    void noTransactionSendsAtOnce() {
        publisher.publishSupervisionAlert(alert());

        assertThat(channel.sent).hasSize(1);
        assertThat(SimpMessageHeaderAccessor.getDestination(channel.sent.get(0).getHeaders()))
                .isEqualTo("/topic/supervision/alerts");
    }

    @Test
    @DisplayName("inside a transaction nothing is sent before the commit, and it is sent after")
    void sentOnlyAfterCommit() {
        TransactionSynchronizationManager.initSynchronization();

        publisher.publishSupervisionAlert(alert());
        assertThat(channel.sent).as("not before the commit").isEmpty();

        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(channel.sent).as("after the commit").hasSize(1);
    }

    @Test
    @DisplayName("a broker failure is logged, never thrown at the use case that already committed")
    void brokerFailureIsSwallowed() {
        AbstractMessageChannel broken = new AbstractMessageChannel() {
            @Override
            protected boolean sendInternal(Message<?> message, long timeout) {
                throw new IllegalStateException("broker down");
            }
        };
        SimpMessagingTemplate template = new SimpMessagingTemplate(broken);
        template.setMessageConverter(template());
        new StompRealtimeEventPublisher(template).publishSupervisionAlert(alert());
        // reaching this line is the assertion
    }

    @Test
    @DisplayName("a rolled-back transaction sends nothing")
    void rollbackSendsNothing() {
        TransactionSynchronizationManager.initSynchronization();

        publisher.publishSupervisionAlert(alert());
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThat(channel.sent).isEmpty();
    }
}
