package com.callverse.host.api.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.callverse.core.application.interfaces.ConversationSupervision.ConversationPage;
import com.callverse.core.application.interfaces.ConversationSupervision.ConversationSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One conversation in the supervisor's list. It carries the operating figures (wait, handle time,
 * SLA outcome) that the customer- and advisor-facing {@code Conversation} deliberately omits: the
 * supervisor is who they are for.
 */
@Schema(name = "SupervisedConversation")
public record SupervisedConversationResponse(
        @Schema(requiredMode = REQUIRED) UUID id,
        @Schema(requiredMode = REQUIRED, example = "ESCALATED",
                allowableValues = {"QUEUED", "ASSIGNED", "ACTIVE", "ESCALATED", "RESOLVED", "ABANDONED"})
                String status,
        @Schema(description = "The queue's skill code; null only for a conversation recorded without a skill",
                example = "FRAUD")
                String skill,
        @Schema(example = "FRAUD") String intent,
        @Schema(requiredMode = REQUIRED, example = "CHAT") String channel,
        @Schema(requiredMode = REQUIRED) Instant queuedAt,
        Instant assignedAt,
        Instant endedAt,
        @Schema(requiredMode = REQUIRED) Party customer,
        // Null while the conversation waits in its queue. Said in the operation's description: springdoc
        // keeps or drops a description beside a $ref depending on generation order, which made the
        // committed contract flap between runs.
        Party advisor,
        @Schema(description = "Seconds waited in the queue; null until measured", example = "42") Integer waitSeconds,
        @Schema(description = "Seconds from assignment to the end; null until it ends", example = "360")
                Integer handleSeconds,
        @Schema(description = "Answered within the skill's SLA target; null until measured") Boolean slaMet,
        @Schema(requiredMode = REQUIRED, example = "12") long messageCount,
        @Schema(description = "Null when nothing has been written") Instant lastMessageAt,
        @Schema(requiredMode = REQUIRED, description = "An escalation is waiting for a supervisor") boolean pendingEscalation) {

    /** A customer or an advisor, as a list row shows them. */
    @Schema(name = "SupervisedParty")
    public record Party(
            @Schema(requiredMode = REQUIRED) UUID id,
            @Schema(requiredMode = REQUIRED, example = "Amina Haddad") String name,
            @Schema(description = "The bank's customer reference; null for an advisor", example = "DEMO-00418")
                    String reference) {}

    public static SupervisedConversationResponse from(ConversationSummary c) {
        return new SupervisedConversationResponse(c.id(), c.status().name(), c.skill(),
                c.intent() == null ? null : c.intent().name(), c.channel(), c.queuedAt(), c.assignedAt(), c.endedAt(),
                new Party(c.customerId(), c.customerName(), c.customerReference()),
                c.advisorId() == null ? null : new Party(c.advisorId(), c.advisorName(), null),
                c.waitSeconds(), c.handleSeconds(), c.slaMet(), c.messageCount(), c.lastMessageAt(),
                c.pendingEscalation());
    }

    /** A page of supervised conversations, in the API's paging shape. */
    @Schema(name = "SupervisedConversationPage")
    public record Page(
            @Schema(requiredMode = REQUIRED) List<SupervisedConversationResponse> content,
            @Schema(requiredMode = REQUIRED) UserResponse.PageInfo page) {

        public static Page from(ConversationPage p) {
            return new Page(p.content().stream().map(SupervisedConversationResponse::from).toList(),
                    new UserResponse.PageInfo(p.page(), p.size(), p.totalElements(), p.totalPages()));
        }
    }
}
