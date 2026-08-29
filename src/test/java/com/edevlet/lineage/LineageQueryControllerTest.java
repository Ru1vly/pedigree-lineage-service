package com.edevlet.lineage;

import com.edevlet.lineage.config.SseProperties;
import com.edevlet.lineage.domain.model.NationalIdentityContext;
import com.edevlet.lineage.domain.model.ProcessingPhase;
import com.edevlet.lineage.domain.model.TaskStatus;
import com.edevlet.lineage.dto.LineageQueryAcceptedResponse;
import com.edevlet.lineage.dto.LineageQueryRequest;
import com.edevlet.lineage.dto.LineageQueryStatusResponse;
import com.edevlet.lineage.infrastructure.security.UserSecurityContextHolder;
import com.edevlet.lineage.infrastructure.security.encryption.TcknEncryptionService;
import com.edevlet.lineage.service.LineageQueryService;
import com.edevlet.lineage.web.LineageQueryController;
import com.edevlet.lineage.web.SseStreamGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = LineageQueryController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class LineageQueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private LineageQueryService queryService;

    @MockBean
    private TcknEncryptionService tcknEncryptionService;

    /**
     * The SSE progress endpoint schedules its polling on the shared scheduler declared by
     * AsyncConfig, admits connections through SseStreamGate, and reads its interval and timeout
     * from SseProperties - none of which this @WebMvcTest slice loads. No test here exercises the
     * stream, so mocks satisfy the controller's constructor without pulling in the whole config.
     */
    @MockBean
    private ThreadPoolTaskScheduler sseProgressScheduler;

    @MockBean
    private SseStreamGate sseStreamGate;

    @MockBean
    private SseProperties sseProperties;

    private NationalIdentityContext mockIdentityContext;

    @BeforeEach
    void setUp() {
        mockIdentityContext = new NationalIdentityContext(
                "user-12345",
                "12345678950",
                Set.of("ROLE_USER"),
                Set.of("lineage:read"),
                "127.0.0.1",
                "MockMvcClient/1.0"
        );
        UserSecurityContextHolder.setContext(mockIdentityContext);
    }

    @AfterEach
    void tearDown() {
        UserSecurityContextHolder.clear();
    }

    @Test
    @DisplayName("POST /api/v1/lineage/queries - Valid request returns HTTP 202 Accepted with Location & Retry-After headers")
    void submitLineageQuery_Success() throws Exception {
        String txId = UUID.randomUUID().toString();

        LineageQueryRequest request = LineageQueryRequest.builder()
                .nationalId("12345678950")
                .generationsDepth(3)
                .includeCertificates(true)
                .documentFormat("PDF")
                .idempotencyKey("idempotency-key-001")
                .build();

        LineageQueryAcceptedResponse acceptedResponse = LineageQueryAcceptedResponse.builder()
                .transactionId(txId)
                .status(TaskStatus.SUBMITTED)
                .currentPhase(ProcessingPhase.INITIATED)
                .progressPercentage(0)
                .retryAfterSeconds(30)
                .createdAt(Instant.now())
                .statusUrl("/api/v1/lineage/queries/" + txId)
                .build();

        given(queryService.submitQuery(any(LineageQueryRequest.class), any(NationalIdentityContext.class)))
                .willReturn(acceptedResponse);

        mockMvc.perform(post("/api/v1/lineage/queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", "/api/v1/lineage/queries/" + txId))
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.transactionId").value(txId))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.currentPhase").value("INITIATED"))
                .andExpect(jsonPath("$.progressPercentage").value(0));
    }

    @Test
    @DisplayName("POST /api/v1/lineage/queries - Invalid TCKN checksum returns HTTP 400 Bad Request")
    void submitLineageQuery_ValidationError() throws Exception {
        LineageQueryRequest invalidRequest = LineageQueryRequest.builder()
                .nationalId("10000000000") // Invalid TCKN checksum
                .generationsDepth(15)     // Exceeds max 10
                .idempotencyKey("")       // Blank key
                .build();

        mockMvc.perform(post("/api/v1/lineage/queries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("GET /api/v1/lineage/queries/{txId} - Status retrieval returns HTTP 200 OK with Cache-Control headers")
    void getLineageQueryStatus_Success() throws Exception {
        String txId = UUID.randomUUID().toString();

        LineageQueryStatusResponse statusResponse = LineageQueryStatusResponse.builder()
                .transactionId(txId)
                .status(TaskStatus.PROCESSING)
                .currentPhase(ProcessingPhase.ANCESTRY_TRAVERSAL)
                .phaseDescription(ProcessingPhase.ANCESTRY_TRAVERSAL.getDescription())
                .progressPercentage(35)
                .retryAfterSeconds(15)
                .createdAt(Instant.now().minusSeconds(10))
                .updatedAt(Instant.now())
                .build();

        given(queryService.getQueryStatus(eq(txId), any(NationalIdentityContext.class)))
                .willReturn(statusResponse);

        mockMvc.perform(get("/api/v1/lineage/queries/{transactionId}", txId))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache, must-revalidate"))
                .andExpect(header().string("Retry-After", "15"))
                .andExpect(jsonPath("$.transactionId").value(txId))
                .andExpect(jsonPath("$.status").value("PROCESSING"))
                .andExpect(jsonPath("$.currentPhase").value("ANCESTRY_TRAVERSAL"))
                .andExpect(jsonPath("$.progressPercentage").value(35));
    }

    @Test
    @DisplayName("DELETE /api/v1/lineage/queries/{txId} - Cancels pending task and returns HTTP 204 No Content")
    void cancelLineageQuery_Success() throws Exception {
        String txId = UUID.randomUUID().toString();

        mockMvc.perform(delete("/api/v1/lineage/queries/{transactionId}", txId))
                .andExpect(status().isNoContent());
    }
}
