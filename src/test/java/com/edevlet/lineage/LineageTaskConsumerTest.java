package com.edevlet.lineage;

import com.edevlet.lineage.domain.model.NationalIdentityContext;
import com.edevlet.lineage.infrastructure.messaging.KafkaConfig;
import com.edevlet.lineage.infrastructure.messaging.LineageQueryMessage;
import com.edevlet.lineage.infrastructure.messaging.LineageTaskConsumer;
import com.edevlet.lineage.infrastructure.pipeline.LineagePipelineOrchestrator;
import com.edevlet.lineage.infrastructure.security.UserSecurityContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.annotation.KafkaListener;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The dead-letter listener's two defects: the container factory it runs on, and what it does with
 * a payload it cannot parse.
 */
@ExtendWith(MockitoExtension.class)
class LineageTaskConsumerTest {

    @Mock
    private LineagePipelineOrchestrator orchestrator;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private LineageTaskConsumer consumer() {
        return new LineageTaskConsumer(orchestrator, objectMapper);
    }

    @Test
    @DisplayName("The DLT listener does not run on the factory whose error handler republishes to the DLT")
    void dltListener_usesItsOwnContainerFactory() throws Exception {
        Method dltListener = LineageTaskConsumer.class.getMethod("consumeDeadLetteredTask", String.class);
        KafkaListener annotation = dltListener.getAnnotation(KafkaListener.class);

        assertThat(annotation).isNotNull();
        // Hosting this listener on "kafkaListenerContainerFactory" is the poison loop: that
        // factory's DefaultErrorHandler republishes any failed record to the DLT at
        // FixedBackOff(0, 0), so a record the DLT listener itself cannot handle is written straight
        // back to the topic it was just read from, forever, hot.
        assertThat(annotation.containerFactory()).isEqualTo("dltKafkaListenerContainerFactory");
        assertThat(annotation.topics()).containsExactly(KafkaConfig.TOPIC_LINEAGE_QUERY_EVENTS_DLT);
    }

    @Test
    @DisplayName("An unparseable dead-lettered payload is logged and skipped, never rethrown")
    void unparseablePayload_isSkippedNotRethrown() {
        // Rethrowing here hands the record back to the container's error handler. On the DLT that
        // is the end of the line - there is nowhere further to forward it - so the only correct
        // outcome is to let the offset advance.
        assertDoesNotThrow(() -> consumer().consumeDeadLetteredTask("{ this is not valid json"));

        verify(orchestrator, never()).finalizeFailure(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("A well-formed dead-lettered payload still reaches the terminal backstop")
    void wellFormedPayload_reachesBackstop() throws Exception {
        LineageQueryMessage message = LineageQueryMessage.builder()
                .transactionId("tx-dlt-1")
                .userId("citizen-7")
                .nationalId("12345678950")
                .submittedAt(Instant.now())
                .build();

        consumer().consumeDeadLetteredTask(objectMapper.writeValueAsString(message));

        verify(orchestrator).finalizeFailure("tx-dlt-1", "MAX_RETRIES_EXCEEDED_DLQ",
                "Task moved to Dead Letter Topic after processing failures.");
    }

    @Test
    @DisplayName("The submitter's origin IP reaches the worker's audit context, not a SYSTEM_ literal")
    void workerContext_carriesSubmitterOrigin() throws Exception {
        LineageQueryMessage message = LineageQueryMessage.builder()
                .transactionId("tx-audit-1")
                .userId("citizen-7")
                .nationalId("12345678950")
                .clientIpAddress("203.0.113.42")
                .clientUserAgent("Mozilla/5.0 (e-Devlet)")
                .submittedAt(Instant.now())
                .build();

        AtomicReference<NationalIdentityContext> observed = new AtomicReference<>();
        // The context is cleared in a finally block, so it has to be captured while the pipeline
        // call is on the stack.
        org.mockito.BDDMockito.willAnswer(invocation -> {
            observed.set(UserSecurityContextHolder.getContext().orElse(null));
            return null;
        }).given(orchestrator).executePipeline(any());

        consumer().consumeTask(objectMapper.writeValueAsString(message));

        assertThat(observed.get()).isNotNull();
        // Worker audit rows previously recorded ipAddress = "SYSTEM_KAFKA_WORKER", so the citizen's
        // origin never reached the compliance trail on the asynchronous half of the pipeline -
        // which is where the lineage query is actually executed.
        assertThat(observed.get().ipAddress()).isEqualTo("203.0.113.42");
        assertThat(observed.get().userAgent()).isEqualTo("Mozilla/5.0 (e-Devlet)");
    }

    @Test
    @DisplayName("A message predating the origin fields records an explicit unknown, not an invented value")
    void missingOrigin_recordsExplicitUnknown() throws Exception {
        LineageQueryMessage legacyMessage = LineageQueryMessage.builder()
                .transactionId("tx-audit-2")
                .userId("citizen-8")
                .nationalId("12345678950")
                .submittedAt(Instant.now())
                .build();

        AtomicReference<NationalIdentityContext> observed = new AtomicReference<>();
        org.mockito.BDDMockito.willAnswer(invocation -> {
            observed.set(UserSecurityContextHolder.getContext().orElse(null));
            return null;
        }).given(orchestrator).executePipeline(any());

        consumer().consumeTask(objectMapper.writeValueAsString(legacyMessage));

        assertThat(observed.get().ipAddress()).isEqualTo("UNKNOWN_ORIGIN");
    }
}
