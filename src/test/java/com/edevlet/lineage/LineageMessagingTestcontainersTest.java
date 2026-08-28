package com.edevlet.lineage;

import com.edevlet.lineage.domain.model.NationalIdentityContext;
import com.edevlet.lineage.domain.model.TaskStatus;
import com.edevlet.lineage.domain.repository.LineageQueryRepository;
import com.edevlet.lineage.dto.LineageQueryAcceptedResponse;
import com.edevlet.lineage.dto.LineageQueryRequest;
import com.edevlet.lineage.infrastructure.messaging.KafkaConfig;
import com.edevlet.lineage.infrastructure.security.UserSecurityContextHolder;
import com.edevlet.lineage.service.LineageQueryService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.debezium.testing.testcontainers.Connector;
import io.debezium.testing.testcontainers.ConnectorConfiguration;
import io.debezium.testing.testcontainers.DebeziumContainer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the outbox -> Debezium CDC -> Kafka -> worker path against REAL infrastructure via
 * Testcontainers, instead of the mocked Redis and H2 database used by every other test in this
 * suite. This replaces the old outbox -> RabbitMQ test: that test's whole premise was guarding
 * against LineageQueryService double-publishing (once via a direct RabbitMQ send, again via
 * OutboxPublisherScheduler's poll) - a hazard that can no longer exist, because nothing in this
 * codebase publishes to the broker directly anymore. A committed transactional_outbox row is the
 * only way an event reaches Kafka, tailed off the PostgreSQL WAL by the Debezium connector
 * registered below (see debezium/lineage-outbox-connector.json for the production equivalent).
 * <p>
 * The regression this test now protects against is the CDC wiring itself: that a submitted query
 * produces exactly one correctly-keyed record on lineage.query.events, and that the real
 * {@code @KafkaListener} path (not a mock) drives the task to COMPLETED.
 */
@Testcontainers
@ExtendWith(DockerAvailableCondition.class)
@SpringBootTest
@ActiveProfiles("testcontainers")
class LineageMessagingTestcontainersTest {

    private static final String KAFKA_INTERNAL_BOOTSTRAP = "kafka:19092";

    // Not @Container-managed: this testcontainers version's JUnit5 extension requires Startable,
    // which Network does not implement. Network.newNetwork() self-registers with Ryuk regardless.
    static final Network NETWORK = Network.newNetwork();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("lineagedb_it")
            .withUsername("lineageuser")
            .withPassword("lineagepass")
            // Vanilla Postgres defaults to wal_level=replica; Debezium's pgoutput plugin needs
            // logical decoding enabled to tail the WAL at all.
            .withCommand("postgres", "-c", "wal_level=logical", "-c", "max_wal_senders=10", "-c", "max_replication_slots=10")
            .withNetwork(NETWORK);

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.0.0")
            .withNetwork(NETWORK)
            // Registers "kafka" as a network alias with its own listener, reachable by other
            // containers (Debezium Connect) on NETWORK - separate from getBootstrapServers()
            // below, which stays the host-mapped address the JVM test process itself connects to.
            .withListener(KAFKA_INTERNAL_BOOTSTRAP);

    @Container
    static final DebeziumContainer DEBEZIUM = new DebeziumContainer(DockerImageName.parse("quay.io/debezium/connect:3.6.0.Final"))
            .withKafka(NETWORK, KAFKA_INTERNAL_BOOTSTRAP)
            .withEnv("CONFIG_STORAGE_REPLICATION_FACTOR", "1")
            .withEnv("OFFSET_STORAGE_REPLICATION_FACTOR", "1")
            .withEnv("STATUS_STORAGE_REPLICATION_FACTOR", "1")
            // The full quay.io/debezium/connect image bundles ~20 connector plugins (MySQL,
            // MongoDB, SQL Server, Oracle, Db2, Vitess, Spanner, ...) that ALL get reflectively
            // scanned at worker startup regardless of which one is actually used, which is slow
            // (multiple minutes under load) - hence the generous startup timeout below. Two ways
            // to avoid that scan were tried and rejected: (1) pointing CONNECT_PLUGIN_PATH directly
            // at .../debezium-connector-postgres (its own leaf directory) instead of the default
            // /kafka/connect - plugin.path entries must each be a directory OF plugin dirs, so
            // pointed at the leaf, Kafka Connect isolates every jar in it into its own classloader
            // instead of bundling them as one connector's dependencies, and PostgresConnector can
            // no longer see its own JDBC driver/kafka-clients; (2) plugin.discovery=service_load,
            // which skips the reflective scan entirely - this connector build has no
            // META-INF/services manifest for its Connector class, so service_load finds zero
            // Debezium connectors regardless of plugin.path.
            .withLogConsumer(frame -> System.out.print("[debezium-connect] " + frame.getUtf8String()))
            .waitingFor(org.testcontainers.containers.wait.strategy.Wait.forHttp("/connectors")
                    .forPort(8083)
                    .withStartupTimeout(Duration.ofMinutes(8)))
            .dependsOn(KAFKA, POSTGRES);

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.2-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    private static volatile boolean connectorRegistered = false;

    // Deliberately NOT @BeforeAll: @BeforeAll runs before the Spring context (and therefore Flyway)
    // has created transactional_outbox, and Debezium's publication.autocreate.mode=filtered fails
    // outright ("No table filters found for filtered publication") if that table doesn't exist yet
    // when the connector starts. @BeforeEach runs after the context (and Flyway) is up, since
    // @Autowired fields below are already populated by the time any @BeforeEach/@Test method runs.
    private static synchronized void registerDebeziumOutboxConnectorOnce() throws InterruptedException {
        if (connectorRegistered) {
            return;
        }
        ConnectorConfiguration outboxConnector = ConnectorConfiguration.forJdbcContainer(POSTGRES)
                .with("topic.prefix", "pedigree")
                .with("slot.name", "debezium_outbox_slot_it")
                .with("publication.autocreate.mode", "filtered")
                .with("plugin.name", "pgoutput")
                .with("table.include.list", "public.transactional_outbox")
                .with("tombstones.on.delete", "false")
                .with("key.converter", "org.apache.kafka.connect.storage.StringConverter")
                .with("value.converter", "org.apache.kafka.connect.storage.StringConverter")
                .with("transforms", "outbox")
                .with("transforms.outbox.type", "io.debezium.transforms.outbox.EventRouter")
                .with("transforms.outbox.route.by.field", "aggregate_type")
                .with("transforms.outbox.table.field.event.key", "aggregate_id")
                .with("transforms.outbox.table.fields.additional.placement", "event_type:header:eventType")
                .with("transforms.outbox.route.topic.replacement", KafkaConfig.TOPIC_LINEAGE_QUERY_EVENTS);

        DEBEZIUM.registerConnector("lineage-outbox-connector-it", outboxConnector);
        DEBEZIUM.ensureConnectorState("lineage-outbox-connector-it", Connector.State.RUNNING);

        // DebeziumContainer.ensureConnectorTaskState() hardcodes a 10s timeout, which isn't always
        // enough for the task to open its replication slot in a loaded environment - poll longer
        // ourselves rather than fail the whole @BeforeAll over a slow-but-healthy startup.
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(60).toMillis();
        Connector.State taskState;
        do {
            taskState = DEBEZIUM.getConnectorTaskState("lineage-outbox-connector-it", 0);
            if (taskState == Connector.State.RUNNING) {
                break;
            }
            Thread.sleep(500);
        } while (System.currentTimeMillis() < deadline);
        if (taskState != Connector.State.RUNNING) {
            throw new IllegalStateException("lineage-outbox-connector-it task 0 never reached RUNNING, was: " + taskState);
        }
        connectorRegistered = true;
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired
    private LineageQueryService queryService;

    @Autowired
    private LineageQueryRepository queryRepository;

    private NationalIdentityContext identity;

    @BeforeEach
    void setUp() throws InterruptedException {
        registerDebeziumOutboxConnectorOnce();
        identity = new NationalIdentityContext(
                "testcontainers-user-" + UUID.randomUUID(),
                "12345678950",
                Set.of("ROLE_USER"),
                Set.of("lineage:read"),
                "10.0.0.1",
                "TestcontainersRunner/1.0"
        );
        UserSecurityContextHolder.setContext(identity);
    }

    @AfterEach
    void tearDown() {
        UserSecurityContextHolder.clear();
    }

    @Test
    @DisplayName("A submitted query's outbox row is CDC-routed to Kafka exactly once and completes via the real worker path")
    void submitQuery_cdcRoutesExactlyOnceAndCompletes() throws Exception {
        LineageQueryRequest request = LineageQueryRequest.builder()
                .nationalId("12345678950")
                .generationsDepth(1)
                .idempotencyKey("testcontainers-" + UUID.randomUUID())
                .build();

        LineageQueryAcceptedResponse accepted = queryService.submitQuery(request, identity);
        String txId = accepted.getTransactionId();
        assertNotNull(txId);
        assertTrue(queryRepository.findByTransactionId(txId).isPresent());

        List<ConsumerRecord<String, String>> records;
        try (KafkaConsumer<String, String> consumer = rawAssertionConsumer()) {
            consumer.subscribe(List.of(KafkaConfig.TOPIC_LINEAGE_QUERY_EVENTS));
            records = drainMatching(consumer, txId, Duration.ofSeconds(30));
        }

        assertEquals(1, records.size(),
                "expected exactly one CDC-routed record keyed by this transactionId - more than one " +
                        "means the outbox connector or its EventRouter transform is misconfigured");

        ConsumerRecord<String, String> record = records.get(0);
        assertEquals(txId, record.key(), "Kafka record key must be the outbox aggregate_id (transactionId)");
        JsonNode payload = JSON.readTree(record.value());
        assertEquals(txId, payload.path("transactionId").asText());

        TaskStatus finalStatus = null;
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(20).toMillis();
        while (System.currentTimeMillis() < deadline) {
            finalStatus = queryRepository.findByTransactionId(txId).orElseThrow().getStatus();
            if (finalStatus == TaskStatus.COMPLETED || finalStatus == TaskStatus.FAILED) {
                break;
            }
            Thread.sleep(300);
        }
        assertEquals(TaskStatus.COMPLETED, finalStatus, "pipeline did not complete via the real Kafka worker path in time");
    }

    private KafkaConsumer<String, String> rawAssertionConsumer() {
        return new KafkaConsumer<>(
                Map.of(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                        ConsumerConfig.GROUP_ID_CONFIG, "it-assertion-" + UUID.randomUUID(),
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(),
                new StringDeserializer());
    }

    /**
     * Polls until the deadline, but once at least one matching record has been seen, only keeps
     * polling for a short settle window rather than the full deadline - long enough to catch a
     * near-simultaneous duplicate (the double-publish scenario this test guards against) without
     * paying the full timeout on every successful run.
     */
    private List<ConsumerRecord<String, String>> drainMatching(KafkaConsumer<String, String> consumer, String key, Duration maxWait) {
        List<ConsumerRecord<String, String>> matched = new ArrayList<>();
        long hardDeadline = System.currentTimeMillis() + maxWait.toMillis();
        long settleDeadline = Long.MAX_VALUE;
        while (System.currentTimeMillis() < Math.min(hardDeadline, settleDeadline)) {
            consumer.poll(Duration.ofMillis(200)).forEach(r -> {
                if (key.equals(r.key())) {
                    matched.add(r);
                }
            });
            if (!matched.isEmpty() && settleDeadline == Long.MAX_VALUE) {
                settleDeadline = System.currentTimeMillis() + 3000;
            }
        }
        return matched;
    }
}
