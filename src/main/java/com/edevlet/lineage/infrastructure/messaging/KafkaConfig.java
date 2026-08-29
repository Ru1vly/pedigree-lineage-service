package com.edevlet.lineage.infrastructure.messaging;

import com.edevlet.lineage.domain.exception.LockContentionException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka consumer/producer wiring for the Debezium-routed outbox topic. There is deliberately no
 * application code that ever produces to {@link #TOPIC_LINEAGE_QUERY_EVENTS} - Debezium's outbox
 * event router SMT is the only writer, tailing transactional_outbox via the PostgreSQL WAL (see
 * the debezium/ connector config and V1__init_lineage_schema.sql). The KafkaTemplate defined here
 * exists solely so failed deliveries can be dead-lettered.
 */
@Slf4j
@Configuration
public class KafkaConfig {

    public static final String TOPIC_LINEAGE_QUERY_EVENTS = "lineage.query.events";
    public static final String TOPIC_LINEAGE_QUERY_EVENTS_DLT = "lineage.query.events.dlt";
    public static final String DLT_CONSUMER_GROUP = "lineage-worker-dlt-group";

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String consumerGroupId;

    @Value("${spring.kafka.consumer.auto-offset-reset:earliest}")
    private String autoOffsetReset;

    @Value("${spring.kafka.listener.concurrency:3}")
    private int listenerConcurrency;

    @Value("${app.kafka.listener.auto-startup:true}")
    private boolean listenerAutoStartup;

    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> consumerProperties = new HashMap<>();
        consumerProperties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        consumerProperties.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroupId);
        consumerProperties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        consumerProperties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        consumerProperties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new DefaultKafkaConsumerFactory<>(consumerProperties);
    }

    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> producerProperties = new HashMap<>();
        producerProperties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        producerProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        return new DefaultKafkaProducerFactory<>(producerProperties);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    /** Lock contention is transient by definition; give the holder a few seconds to finish. */
    private static final long LOCK_CONTENTION_RETRY_INTERVAL_MS = 2_000L;
    private static final long LOCK_CONTENTION_MAX_RETRIES = 4L;

    private static final BackOff STRAIGHT_TO_DLT = new FixedBackOff(0L, 0L);
    private static final BackOff LOCK_CONTENTION_BACKOFF =
            new FixedBackOff(LOCK_CONTENTION_RETRY_INTERVAL_MS, LOCK_CONTENTION_MAX_RETRIES);

    /**
     * Domain-level retries are re-queued as a fresh transactional_outbox row (see
     * PipelineFailureHandler.recordFailureAndMaybeRetry), which flows back through Debezium
     * like any other event. A listener exception here therefore only ever means retries are
     * already exhausted or something unexpected happened - so there is nothing to gain from
     * broker-level redelivery. FixedBackOff(0, 0) sends straight to the DLT on the first failure.
     * Partition -1 lets the DLT topic's own partitioner choose.
     *
     * <p>{@link LockContentionException} is the one exception that must not follow that rule. It
     * means another worker currently holds the task, which is not a failure of this record and
     * certainly not poison - dead-lettering it on first contact would turn ordinary contention into
     * an operator page, and the alternative the orchestrator used to take (returning normally) acked
     * and silently dropped the record. It gets a bounded backoff instead: redeliver a few times, and
     * only then dead-letter, by which point the holder is genuinely stuck.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(TOPIC_LINEAGE_QUERY_EVENTS_DLT, -1));

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, STRAIGHT_TO_DLT);
        errorHandler.setBackOffFunction((record, exception) ->
                isLockContention(exception) ? LOCK_CONTENTION_BACKOFF : STRAIGHT_TO_DLT);
        return errorHandler;
    }

    /**
     * Spring Kafka wraps listener exceptions (ListenerExecutionFailedException and friends), so the
     * cause chain has to be walked rather than the top-level type inspected.
     */
    private static boolean isLockContention(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof LockContentionException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory, DefaultErrorHandler kafkaErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(listenerConcurrency);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        factory.setAutoStartup(listenerAutoStartup);
        return factory;
    }

    /**
     * The DLT listener must NOT run on {@link #kafkaListenerContainerFactory}. That factory's error
     * handler republishes any failed record to the DLT, so a record the DLT listener itself cannot
     * handle - an unparseable payload is the obvious one - gets written straight back to the topic
     * it was just read from, read again, and fails again, at {@code FixedBackOff(0, 0)}: a hot
     * infinite loop that saturates a broker and a worker core with one bad message.
     *
     * <p>The dead-letter topic is the end of the line by definition; there is nowhere further to
     * forward a record. This handler therefore logs and lets the container commit the offset,
     * so a poison record is recorded once and moved past. Recovery from there is the operator's
     * job - the record is still in the DLT's retention window, which is the point of the topic.
     *
     * <p>Concurrency is fixed at 1: the DLT is a low-volume operator surface, not a throughput
     * path, and a single consumer keeps its log ordered and readable during an incident.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> dltKafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(1);
        factory.setCommonErrorHandler(new DefaultErrorHandler(
                (record, exception) -> log.error(
                        "Unrecoverable record on the dead letter topic; logging and skipping it so the "
                                + "offset advances. topic={}, partition={}, offset={}, key={}",
                        record.topic(), record.partition(), record.offset(), record.key(), exception),
                STRAIGHT_TO_DLT));
        factory.setAutoStartup(listenerAutoStartup);
        return factory;
    }
}
