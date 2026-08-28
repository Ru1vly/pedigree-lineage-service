package com.edevlet.lineage.infrastructure.messaging;

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

    /**
     * Domain-level retries are re-queued as a fresh transactional_outbox row (see
     * LineagePipelineOrchestrator.recordFailureAndMaybeRetry), which flows back through Debezium
     * like any other event. A listener exception here therefore only ever means retries are
     * already exhausted or something unexpected happened - so there is nothing to gain from
     * broker-level redelivery. FixedBackOff(0, 0) sends straight to the DLT on the first failure.
     * Partition -1 lets the DLT topic's own partitioner choose.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
                (record, exception) -> new TopicPartition(TOPIC_LINEAGE_QUERY_EVENTS_DLT, -1));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(0L, 0L));
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
}
