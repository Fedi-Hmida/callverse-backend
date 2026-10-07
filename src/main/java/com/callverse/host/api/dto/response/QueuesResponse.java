package com.callverse.host.api.dto.response;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.callverse.core.application.interfaces.LiveOperations.QueueDepth;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** The skill queues the caller may see, with how many wait and for how long the oldest has. */
@Schema(name = "Queues")
public record QueuesResponse(@Schema(requiredMode = REQUIRED) List<Queue> queues) {

    @Schema(name = "Queue")
    public record Queue(
            @Schema(requiredMode = REQUIRED, example = "FRAUD") String skill,
            @Schema(requiredMode = REQUIRED, example = "3") long waiting,
            @Schema(description = "Seconds the oldest waiting conversation has waited; null when none waits",
                    example = "42")
                    Integer oldestWaitSeconds) {

        public static Queue from(QueueDepth depth) {
            return new Queue(depth.skill(), depth.waiting(), depth.oldestWaitSeconds());
        }
    }

    public static QueuesResponse from(List<QueueDepth> depths) {
        return new QueuesResponse(depths.stream().map(Queue::from).toList());
    }
}
