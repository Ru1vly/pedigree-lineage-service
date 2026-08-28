package com.edevlet.lineage;

import com.edevlet.lineage.config.TestConfig;
import com.edevlet.lineage.domain.model.NationalIdentityContext;
import com.edevlet.lineage.domain.model.TaskStatus;
import com.edevlet.lineage.domain.repository.LineageQueryRepository;
import com.edevlet.lineage.dto.LineageQueryAcceptedResponse;
import com.edevlet.lineage.dto.LineageQueryRequest;
import com.edevlet.lineage.dto.LineageQueryStatusResponse;
import com.edevlet.lineage.infrastructure.security.UserSecurityContextHolder;
import com.edevlet.lineage.service.LineageQueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestConfig.class)
@Transactional
class LineageIntegrationTest {

    @Autowired
    private LineageQueryService queryService;

    @Autowired
    private LineageQueryRepository queryRepository;

    private NationalIdentityContext userIdentity;

    @BeforeEach
    void setUp() {
        userIdentity = new NationalIdentityContext(
                "test-user-999",
                "12345678950",
                Set.of("ROLE_USER"),
                Set.of("lineage:read"),
                "192.168.1.10",
                "IntegrationTestRunner/1.0"
        );
        UserSecurityContextHolder.setContext(userIdentity);
    }

    @AfterEach
    void tearDown() {
        UserSecurityContextHolder.clear();
    }

    @Test
    @DisplayName("End-to-End Task Submission, Database Outbox Persistence & Status Retrieval")
    void testEndToEndTaskLifecycle() {
        String idempotencyKey = "integration-key-" + UUID.randomUUID();

        LineageQueryRequest request = LineageQueryRequest.builder()
                .nationalId("12345678950")
                .generationsDepth(3)
                .includeCertificates(true)
                .documentFormat("PDF")
                .idempotencyKey(idempotencyKey)
                .build();

        // 1. Submit Query Task
        LineageQueryAcceptedResponse accepted = queryService.submitQuery(request, userIdentity);

        assertNotNull(accepted);
        assertNotNull(accepted.getTransactionId());
        assertEquals(TaskStatus.SUBMITTED, accepted.getStatus());
        assertEquals(30, accepted.getRetryAfterSeconds());

        // 2. Query Status via Service
        LineageQueryStatusResponse statusResponse = queryService.getQueryStatus(accepted.getTransactionId(), userIdentity);

        assertNotNull(statusResponse);
        assertEquals(accepted.getTransactionId(), statusResponse.getTransactionId());
        assertEquals(TaskStatus.SUBMITTED, statusResponse.getStatus());
        assertEquals(0, statusResponse.getProgressPercentage());

        // 3. Verify Database Entity Persistence
        assertTrue(queryRepository.findByTransactionId(accepted.getTransactionId()).isPresent());
    }
}
