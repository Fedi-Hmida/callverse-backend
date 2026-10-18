package com.callverse.infrastructure.persistence;

import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.exceptions.SimulationRunAlreadyExistsException;
import com.callverse.core.application.interfaces.AdvisorAgent.AdvisorResponse;
import com.callverse.core.application.interfaces.AdvisorAgent.Source;
import com.callverse.core.application.interfaces.AdvisorAgent.ToolCall;
import com.callverse.core.application.interfaces.ConversationSimulationStore;
import com.callverse.core.application.interfaces.ConversationSimulationStore.SimulationRecord;
import com.callverse.core.application.interfaces.ConversationSimulationStore.SimulationSetup;
import com.callverse.core.application.interfaces.ConversationSimulationStore.TranscriptMessage;
import com.callverse.core.application.interfaces.SimulatorAgent.SimulatorRequest;
import com.callverse.core.application.interfaces.SimulatorAgent.SimulatorResponse;
import com.callverse.core.domain.entities.AgentDecision;
import com.callverse.core.domain.entities.ControlStrategy;
import com.callverse.core.domain.entities.Conversation;
import com.callverse.core.domain.entities.Customer;
import com.callverse.core.domain.entities.Message;
import com.callverse.core.domain.entities.Scenario;
import com.callverse.core.domain.entities.SimulationRun;
import com.callverse.core.domain.enums.AgentType;
import com.callverse.core.domain.enums.ConversationStatus;
import com.callverse.core.domain.enums.MessageSender;
import com.callverse.core.domain.enums.RunStatus;
import com.callverse.infrastructure.persistence.repositories.AgentDecisionRepository;
import com.callverse.infrastructure.persistence.repositories.ControlStrategyRepository;
import com.callverse.infrastructure.persistence.repositories.ConversationRepository;
import com.callverse.infrastructure.persistence.repositories.CustomerRepository;
import com.callverse.infrastructure.persistence.repositories.MessageRepository;
import com.callverse.infrastructure.persistence.repositories.ScenarioRepository;
import com.callverse.infrastructure.persistence.repositories.SimulationRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Persists conversational simulation data without passing through the live conversation adapter. */
@Component
@RequiredArgsConstructor
class ConversationSimulationStoreAdapter implements ConversationSimulationStore {

    private final SimulationRunRepository runs;
    private final ScenarioRepository scenarios;
    private final ControlStrategyRepository strategies;
    private final CustomerRepository customers;
    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final AgentDecisionRepository decisions;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public SimulationRecord start(SimulationSetup setup, Instant now) {
        Scenario scenario = scenarios.findById(setup.scenarioId())
                .orElseThrow(() -> new ResourceNotFoundException("Scenario", setup.scenarioId()));
        ControlStrategy strategy = strategies.findByCode(setup.strategyCode())
                .orElseThrow(() -> new ResourceNotFoundException("Control strategy", setup.strategyCode()));
        if (runs.existsByScenarioIdAndStrategyIdAndSeed(scenario.getId(), strategy.getId(), setup.seed())) {
            throw new SimulationRunAlreadyExistsException(scenario.getId(), strategy.getCode(), setup.seed());
        }

        SimulationRun run = new SimulationRun();
        run.setScenario(scenario);
        run.setStrategy(strategy);
        run.setSeed(setup.seed());
        run.setStatus(RunStatus.RUNNING);
        run.setStartedAt(now);
        try {
            runs.saveAndFlush(run);
        } catch (DataIntegrityViolationException exception) {
            throw new SimulationRunAlreadyExistsException(scenario.getId(), strategy.getCode(), setup.seed());
        }

        Customer customer = new Customer();
        customer.setExternalRef("SIM-" + UUID.randomUUID());
        customer.setFirstName("Client");
        customer.setLastName("Simule");
        customer.setRegion("SIMULATION");
        customer.setSegment(setup.customerSegment());
        customer.setTenureMonths(setup.tenureMonths());
        customer.setSimulated(true);
        customers.save(customer);

        Conversation conversation = new Conversation();
        conversation.setCustomer(customer);
        conversation.setRunId(run.getId());
        conversation.setStatus(ConversationStatus.ACTIVE);
        conversation.setQueuedAt(now);
        conversation.setAssignedAt(now);
        conversations.save(conversation);

        return toRecord(run, conversation, 0);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SimulationRecord> find(UUID runId) {
        // A conversation simulation always has its conversation (created with the run); a run without
        // one is a digital twin run, read through /api/v1/twin-runs instead.
        return runs.findById(runId).flatMap(run -> conversations.findByRunId(runId).stream().findFirst()
                .map(conversation -> toRecord(run, conversation, messages.countByConversationId(conversation.getId()))));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TranscriptMessage> transcript(UUID conversationId) {
        return messages.findByConversationIdOrderBySentAtAscIdAsc(conversationId).stream()
                .map(message -> new TranscriptMessage(
                        message.getId(), message.getSender(), message.getContent(), message.getSentAt()))
                .toList();
    }

    @Override
    @Transactional
    public TranscriptMessage appendCustomerMessage(UUID conversationId, String content, Instant now) {
        return appendMessage(conversationId, MessageSender.CUSTOMER, content, false, null, null, now);
    }

    @Override
    @Transactional
    public void appendAdvisorMessage(UUID conversationId, AdvisorResponse response, Instant now) {
        List<Map<String, Object>> sources = response.sources().stream()
                .map(ConversationSimulationStoreAdapter::sourceMap)
                .toList();
        List<Map<String, Object>> toolCalls = response.toolCalls().stream()
                .map(ConversationSimulationStoreAdapter::toolCallMap)
                .toList();
        appendMessage(
                conversationId,
                MessageSender.ADVISOR,
                response.reply(),
                true,
                objectMapper.valueToTree(sources),
                objectMapper.valueToTree(toolCalls),
                now);
    }

    @Override
    @Transactional
    public void recordSimulatorDecision(
            UUID runId, int simTime, SimulatorRequest observation, SimulatorResponse action) {
        saveDecision(
                runId,
                AgentType.CLIENT_SIMULATOR,
                simTime,
                objectMapper.valueToTree(observation),
                objectMapper.valueToTree(Map.of(
                        "content", action.content() == null ? "" : action.content(),
                        "outcome", action.outcome().name(),
                        "state", action.state())),
                "Generated the next simulated customer turn.");
    }

    @Override
    @Transactional
    public void recordAdvisorDecision(UUID runId, int simTime, String message, AdvisorResponse action) {
        saveDecision(
                runId,
                AgentType.CUSTOMER_ADVISOR,
                simTime,
                objectMapper.valueToTree(Map.of("customer_message", message)),
                objectMapper.valueToTree(Map.of(
                        "reply", action.reply(),
                        "intent", action.intent(),
                        "confidence", action.confidence(),
                        "action_type", action.actionType(),
                        "sources", action.sources().stream().map(ConversationSimulationStoreAdapter::sourceMap).toList(),
                        "tool_calls", action.toolCalls().stream()
                                .map(ConversationSimulationStoreAdapter::toolCallMap)
                                .toList())),
                "Generated the simulated Advisor turn.");
    }

    @Override
    @Transactional
    public void complete(UUID runId, UUID conversationId, ConversationStatus conversationStatus, Instant now) {
        SimulationRun run = findRun(runId);
        Conversation conversation = findConversation(runId, conversationId);
        if (conversationStatus != ConversationStatus.RESOLVED && conversationStatus != ConversationStatus.ABANDONED
                && conversationStatus != ConversationStatus.ESCALATED) {
            throw new IllegalArgumentException("Simulation conversations must end RESOLVED, ABANDONED or ESCALATED.");
        }
        conversation.setStatus(conversationStatus);
        conversation.setEndedAt(now);
        conversation.setHandleSeconds((int) Math.min(
                Integer.MAX_VALUE, Math.max(0, Duration.between(conversation.getAssignedAt(), now).toSeconds())));
        run.setStatus(RunStatus.COMPLETED);
        run.setEndedAt(now);
    }

    @Override
    @Transactional
    public void fail(UUID runId, String safeErrorMessage, Instant now) {
        runs.findById(runId).ifPresent(run -> {
            run.setStatus(RunStatus.FAILED);
            run.setEndedAt(now);
            run.setErrorMessage(safeErrorMessage);
        });
    }

    private TranscriptMessage appendMessage(
            UUID conversationId,
            MessageSender sender,
            String content,
            boolean aiGenerated,
            com.fasterxml.jackson.databind.JsonNode sources,
            com.fasterxml.jackson.databind.JsonNode toolCalls,
            Instant now) {
        Conversation conversation = conversations.findById(conversationId)
                .filter(value -> value.getRunId() != null)
                .orElseThrow(() -> new ResourceNotFoundException("Simulated conversation", conversationId));
        if (conversation.getStatus() != ConversationStatus.ACTIVE) {
            throw new IllegalStateException("The simulated conversation is no longer active.");
        }
        Message message = new Message();
        message.setConversation(conversation);
        message.setSender(sender);
        message.setContent(content);
        message.setAiGenerated(aiGenerated);
        message.setSources(sources);
        message.setToolCalls(toolCalls);
        message.setSentAt(now);
        Message saved = messages.save(message);
        return new TranscriptMessage(saved.getId(), sender, content, saved.getSentAt());
    }

    private void saveDecision(
            UUID runId,
            AgentType agentType,
            int simTime,
            com.fasterxml.jackson.databind.JsonNode observation,
            com.fasterxml.jackson.databind.JsonNode action,
            String reason) {
        AgentDecision decision = new AgentDecision();
        decision.setRun(findRun(runId));
        decision.setAgentType(agentType);
        decision.setSimTime(Math.max(0, simTime));
        decision.setObservation(observation);
        decision.setAction(action);
        decision.setReason(reason);
        decisions.save(decision);
    }

    private SimulationRun findRun(UUID runId) {
        return runs.findById(runId).orElseThrow(() -> new ResourceNotFoundException("Simulation run", runId));
    }

    private Conversation findConversation(UUID runId, UUID conversationId) {
        return conversations.findById(conversationId)
                .filter(value -> runId.equals(value.getRunId()))
                .orElseThrow(() -> new ResourceNotFoundException("Simulated conversation", conversationId));
    }

    private static Map<String, Object> sourceMap(Source source) {
        return Map.of("kb_article_id", source.knowledgeBaseArticleId(), "score", source.score());
    }

    private static Map<String, Object> toolCallMap(ToolCall toolCall) {
        return Map.of(
                "tool",
                toolCall.tool(),
                "args",
                toolCall.args(),
                "result_summary",
                toolCall.resultSummary(),
                "ok",
                toolCall.ok());
    }

    private static SimulationRecord toRecord(SimulationRun run, Conversation conversation, long messageCount) {
        return new SimulationRecord(
                run.getId(),
                conversation == null ? null : conversation.getId(),
                conversation == null ? null : conversation.getCustomer().getId(),
                run.getStatus(),
                conversation == null ? null : conversation.getStatus(),
                messageCount,
                run.getErrorMessage(),
                run.getStartedAt(),
                run.getEndedAt());
    }
}
