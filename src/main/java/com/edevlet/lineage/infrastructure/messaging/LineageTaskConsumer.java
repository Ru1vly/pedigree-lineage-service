package com.edevlet.lineage.infrastructure.messaging;

import com.edevlet.lineage.domain.model.NationalIdentityContext;
import com.edevlet.lineage.infrastructure.pipeline.LineagePipelineOrchestrator;
import com.edevlet.lineage.infrastructure.security.UserSecurityContextHolder;
import com.edevlet.lineage.infrastructure.tracing.TracingMdcFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class LineageTaskConsumer {

    private final LineagePipelineOrchestrator pipelineOrchestrator;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = KafkaConfig.TOPIC_LINEAGE_QUERY_EVENTS,
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory")
    public void consumeTask(String payload) throws JsonProcessingException {
        LineageQueryMessage message = objectMapper.readValue(payload, LineageQueryMessage.class);
        log.info("Received lineage query task from Kafka (Debezium outbox CDC event). transactionId={}, userId={}",
                message.getTransactionId(), message.getUserId());

        try {
            populateExecutionContext(message);
            pipelineOrchestrator.executePipeline(message);
        } finally {
            cleanupExecutionContext();
        }
    }

    private void populateExecutionContext(LineageQueryMessage message) {
        String effectiveTraceId = message.getTraceId() != null ? message.getTraceId() : message.getTransactionId();
        MDC.put(TracingMdcFilter.MDC_TRACE_ID, effectiveTraceId);
        MDC.put(TracingMdcFilter.MDC_TRANSACTION_ID, message.getTransactionId());
        MDC.put(TracingMdcFilter.MDC_USER_ID, message.getUserId());

        NationalIdentityContext workerContext = new NationalIdentityContext(
                message.getUserId(),
                message.getNationalId(),
                Set.of("ROLE_USER"),
                Set.of("lineage:read"),
                "SYSTEM_KAFKA_WORKER",
                "SpringKafkaWorker/1.0"
        );
        UserSecurityContextHolder.setContext(workerContext);
    }

    private void cleanupExecutionContext() {
        UserSecurityContextHolder.clear();
        MDC.clear();
    }

    @KafkaListener(
            topics = KafkaConfig.TOPIC_LINEAGE_QUERY_EVENTS_DLT,
            groupId = KafkaConfig.DLT_CONSUMER_GROUP,
            containerFactory = "kafkaListenerContainerFactory")
    public void consumeDeadLetteredTask(String payload) throws JsonProcessingException {
        LineageQueryMessage message = objectMapper.readValue(payload, LineageQueryMessage.class);
        log.error("CRITICAL: Message received in Dead Letter Topic (DLT). Task transactionId={}, userId={}. Manual intervention required.",
                message.getTransactionId(), message.getUserId());
        pipelineOrchestrator.finalizeFailure(message.getTransactionId(), "MAX_RETRIES_EXCEEDED_DLQ", "Task moved to Dead Letter Topic after processing failures.");
    }
}
