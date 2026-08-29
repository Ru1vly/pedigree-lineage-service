package com.edevlet.lineage.infrastructure.messaging;

import com.edevlet.lineage.domain.model.NationalIdentityContext;
import com.edevlet.lineage.infrastructure.pipeline.LineagePipelineOrchestrator;
import com.edevlet.lineage.infrastructure.pipeline.PipelineRetryProperties;
import com.edevlet.lineage.infrastructure.security.UserSecurityContextHolder;
import com.edevlet.lineage.infrastructure.tracing.TracingMdcFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class LineageTaskConsumer {

    private final LineagePipelineOrchestrator pipelineOrchestrator;
    private final ObjectMapper objectMapper;
    private final PipelineRetryProperties retryProperties;

    @KafkaListener(
            topics = KafkaConfig.TOPIC_LINEAGE_QUERY_EVENTS,
            groupId = "${spring.kafka.consumer.group-id}",
            containerFactory = "kafkaListenerContainerFactory")
    public void consumeTask(String payload) throws JsonProcessingException {
        LineageQueryMessage message = objectMapper.readValue(payload, LineageQueryMessage.class);
        log.info("Received lineage query task from Kafka (Debezium outbox CDC event). transactionId={}, userId={}",
                message.getTransactionId(), message.getUserId());

        awaitRetrySchedule(message);

        try {
            populateExecutionContext(message);
            pipelineOrchestrator.executePipeline(message);
        } finally {
            cleanupExecutionContext();
        }
    }

    /**
     * Holds a re-queued attempt until the backoff its producer stamped on it has elapsed.
     *
     * <p>A pipeline retry is published as an outbox row and Debezium puts it on Kafka within
     * milliseconds - the outbox exists to publish immediately and there is no delay to be taken
     * there. So a failing task's entire retry budget used to arrive back-to-back at a legacy census
     * backend that was already struggling, with only the circuit breaker between the two.
     *
     * <p>This does block a listener thread, which is a real cost and the reason it is capped at
     * {@code app.pipeline.retry.max-consumer-deferral}: the alternative is retrying a failing
     * backend as fast as the broker can deliver, and the waits involved are seconds. A delay topic
     * is the right answer if the budget ever needs to stretch beyond that. Initial submissions
     * carry no schedule and are never delayed.
     */
    private void awaitRetrySchedule(LineageQueryMessage message) {
        if (message.getRetryNotBefore() == null) {
            return;
        }

        Duration remaining = Duration.between(Instant.now(), message.getRetryNotBefore());
        if (remaining.isNegative() || remaining.isZero()) {
            return;
        }

        Duration cappedWait = remaining.compareTo(retryProperties.getMaxConsumerDeferral()) > 0
                ? retryProperties.getMaxConsumerDeferral()
                : remaining;

        log.info("Deferring retry attempt {} for transactionId={} by {} before re-entering the pipeline.",
                message.getRetryAttempt(), message.getTransactionId(), cappedWait);
        try {
            Thread.sleep(cappedWait.toMillis());
        } catch (InterruptedException interrupted) {
            // A shutdown or a container stop. Restore the flag and let the attempt proceed
            // immediately rather than swallowing the signal; the record is not yet acknowledged,
            // so a shutdown here simply redelivers it.
            Thread.currentThread().interrupt();
            log.warn("Interrupted while deferring retry for transactionId={}; proceeding without the remaining backoff.",
                    message.getTransactionId());
        }
    }

    private void populateExecutionContext(LineageQueryMessage message) {
        String effectiveTraceId = message.getTraceId() != null ? message.getTraceId() : message.getTransactionId();
        MDC.put(TracingMdcFilter.MDC_TRACE_ID, effectiveTraceId);
        MDC.put(TracingMdcFilter.MDC_TRANSACTION_ID, message.getTransactionId());
        MDC.put(TracingMdcFilter.MDC_USER_ID, message.getUserId());

        // The submitter's origin IP and user agent travel with the message so audit rows written on
        // this worker attribute the action to the citizen who made the request, not to the worker
        // that happened to process it. Messages enqueued before those fields existed carry null;
        // an explicit unknown marker is recorded rather than a plausible-looking invented value.
        NationalIdentityContext workerContext = new NationalIdentityContext(
                message.getUserId(),
                message.getNationalId(),
                Set.of("ROLE_USER"),
                Set.of("lineage:read"),
                originOrUnknown(message.getClientIpAddress()),
                originOrUnknown(message.getClientUserAgent())
        );
        UserSecurityContextHolder.setContext(workerContext);
    }

    /**
     * An audit trail that says "unknown" is auditable; one that says "SYSTEM_KAFKA_WORKER" for a
     * citizen's request is a wrong answer that reads like a right one.
     */
    private static String originOrUnknown(String value) {
        return value != null && !value.isBlank() ? value : "UNKNOWN_ORIGIN";
    }

    private void cleanupExecutionContext() {
        UserSecurityContextHolder.clear();
        MDC.clear();
    }

    /**
     * Runs on {@code dltKafkaListenerContainerFactory}, NOT the main factory. The main factory's
     * error handler republishes failures to this very topic, so hosting this listener there turned
     * any unparseable dead-lettered payload into a hot infinite loop - read, fail to parse,
     * republish to the DLT, read again - at {@code FixedBackOff(0, 0)}. See KafkaConfig.
     *
     * <p>The parse is also handled here rather than thrown: a malformed payload on the DLT carries
     * no transactionId to act on, so there is no task to finalize and nothing an operator gains
     * from a stack trace on every redelivery. It is logged with the raw payload once and the offset
     * moves on.
     */
    @KafkaListener(
            topics = KafkaConfig.TOPIC_LINEAGE_QUERY_EVENTS_DLT,
            groupId = KafkaConfig.DLT_CONSUMER_GROUP,
            containerFactory = "dltKafkaListenerContainerFactory")
    public void consumeDeadLetteredTask(String payload) {
        LineageQueryMessage message;
        try {
            message = objectMapper.readValue(payload, LineageQueryMessage.class);
        } catch (JsonProcessingException parseFailure) {
            log.error("CRITICAL: Unparseable payload on the Dead Letter Topic; it names no task to "
                    + "finalize and is being skipped. Raw payload: {}", payload, parseFailure);
            return;
        }

        log.error("CRITICAL: Message received in Dead Letter Topic (DLT). Task transactionId={}, userId={}. Manual intervention required.",
                message.getTransactionId(), message.getUserId());
        pipelineOrchestrator.finalizeFailure(message.getTransactionId(), "MAX_RETRIES_EXCEEDED_DLQ", "Task moved to Dead Letter Topic after processing failures.");
    }
}
