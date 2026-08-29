package com.edevlet.lineage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs every migration against a real PostgreSQL and validates the JPA mappings against the result.
 *
 * <p>Why this exists: the fast unit profile (application-test.yml) disables Flyway and lets
 * Hibernate build an H2 schema with {@code ddl-auto: update}. Production runs the opposite -
 * Flyway applies the migrations and Hibernate is set to {@code validate}. So the migrations were
 * never executed by {@code mvn test}, and a migration that failed to apply, or that drifted from
 * the entity mappings, would first be discovered on a production deployment. The only test that
 * did exercise them stood up Kafka, Debezium and Connect as well, which is far too heavy to be
 * the safety net for a schema change.
 *
 * <p>This test starts Postgres alone, so it is cheap enough to run on every build. The
 * {@code ddl-auto: validate} in the testcontainers profile is the actual assertion: if any entity
 * disagrees with the migrated schema, the application context fails to start and every test here
 * fails with it.
 */
@SpringBootTest
@ActiveProfiles("testcontainers")
@Testcontainers
@ExtendWith(DockerAvailableCondition.class)
class FlywaySchemaMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("lineagedb")
            .withUsername("lineageuser")
            .withPassword("lineagepass");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // No Kafka broker is started here, so the listener container must not spin up a consumer
        // thread that retries forever against nothing.
        //
        // The Kafka and Redis autoconfigurations are deliberately NOT excluded: RedisConfig needs
        // the RedisConnectionFactory that RedisAutoConfiguration supplies, and Lettuce connects
        // lazily, so the bean exists without anything ever contacting a Redis server. Excluding it
        // fails the context on a missing bean rather than on anything to do with the schema.
        registry.add("app.kafka.listener.auto-startup", () -> "false");
    }

    @Autowired
    private DataSource dataSource;

    private List<String> queryColumn(String sql, String column) throws Exception {
        List<String> values = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            while (resultSet.next()) {
                values.add(resultSet.getString(column));
            }
        }
        return values;
    }

    @Test
    @DisplayName("Every migration in db/migration applied successfully")
    void allMigrationsApplied() throws Exception {
        List<String> versions = queryColumn(
                "SELECT version FROM flyway_schema_history WHERE success = true AND version IS NOT NULL "
                        + "ORDER BY installed_rank", "version");

        assertThat(versions).containsExactly("1", "2", "3", "4", "5");

        List<String> failed = queryColumn(
                "SELECT version FROM flyway_schema_history WHERE success = false", "version");
        assertThat(failed).isEmpty();
    }

    @Test
    @DisplayName("V5 records the national_id encryption contract where a DBA reading the column will see it")
    void nationalIdColumnsCarryTheirEncryptionContract() throws Exception {
        // V2 is named encrypt_tckn_column and encrypts nothing - it widens the column, and no
        // backfill is possible in SQL because the key is deliberately not in the database. So the
        // column holds a mixture of formats, and the only place to say so is on the column itself.
        List<String> comments = queryColumn(
                "SELECT col_description(c.oid, a.attnum) AS comment "
                        + "FROM pg_class c "
                        + "JOIN pg_attribute a ON a.attrelid = c.oid "
                        + "WHERE c.relname IN ('lineage_queries', 'lineage_audit_logs') "
                        + "AND a.attname = 'national_id'", "comment");

        assertThat(comments).hasSize(2);
        assertThat(comments).allSatisfy(comment ->
                assertThat(comment).contains("Encrypted at rest by the application"));
    }

    @Test
    @DisplayName("The migrated schema satisfies Hibernate's validate, exactly as in production")
    void schemaValidatesAgainstEntities() throws Exception {
        // Reaching this method at all means the context started under ddl-auto: validate against
        // the Flyway-built schema. Asserting the core tables exist keeps the intent explicit.
        List<String> tables = queryColumn(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", "table_name");

        assertThat(tables).contains("lineage_queries", "transactional_outbox", "lineage_audit_logs");
    }

    @Test
    @DisplayName("V3 scoped idempotency uniqueness to the user, and V1's global constraint is gone")
    void idempotencyUniquenessIsScopedToUser() throws Exception {
        List<String> constraints = queryColumn(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'lineage_queries'::regclass AND contype = 'u'",
                "conname");

        // A global unique idempotency_key let one citizen's key collide with another's and hand
        // back a stranger's task - see V3__scope_idempotency_key_to_user.sql.
        assertThat(constraints).contains("uq_lineage_queries_user_idempotency");
        assertThat(constraints).doesNotContain("lineage_queries_idempotency_key_key");
    }
}
