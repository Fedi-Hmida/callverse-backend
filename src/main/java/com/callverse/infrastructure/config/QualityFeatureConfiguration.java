package com.callverse.infrastructure.config;

import com.callverse.core.application.features.quality.commands.QualityEvaluationCommandHandler;
import com.callverse.core.application.features.quality.commands.RequestQualityEvaluationCommandHandler;
import com.callverse.core.application.interfaces.QualityEvaluationQueue;
import com.callverse.core.application.interfaces.ConversationLifecycle;
import com.callverse.core.application.interfaces.ConversationMessages;
import com.callverse.core.application.interfaces.QualityAgent;
import com.callverse.core.application.interfaces.QualityEvaluationStore;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Wires asynchronous Quality Analyst evaluations after conversation resolution. */
@Configuration
public class QualityFeatureConfiguration {

    @Bean
    RequestQualityEvaluationCommandHandler requestQualityEvaluation(
            ConversationLifecycle conversations, ConversationMessages messages,
            QualityEvaluationQueue queue) {
        return new RequestQualityEvaluationCommandHandler(
                conversations, messages, queue);
    }

    @Bean
    QualityEvaluationCommandHandler qualityEvaluationCommandHandler(
            QualityAgent agent,
            ConversationLifecycle conversations,
            ConversationMessages messages,
            QualityEvaluationStore evaluations) {
        return new QualityEvaluationCommandHandler(agent, conversations, messages, evaluations);
    }

    @Bean(name = "qualityEvaluationExecutor")
    TaskExecutor qualityEvaluationExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(25);
        executor.setThreadNamePrefix("quality-evaluation-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        return executor;
    }
}
