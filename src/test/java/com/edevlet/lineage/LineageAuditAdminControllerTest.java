package com.edevlet.lineage;

import com.edevlet.lineage.domain.model.LineageAuditLog;
import com.edevlet.lineage.domain.repository.LineageAuditLogRepository;
import com.edevlet.lineage.web.LineageAuditAdminController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The admin audit endpoint's two former defects, which are the worst pair in the repository
 * because they cancel out the encryption everything else is built around.
 *
 * <p>It returned the {@code LineageAuditLog} entity directly. Hibernate decrypts {@code nationalId}
 * on load, so every response carried citizens' TCKNs in cleartext - and with no filter supplied it
 * fell through to {@code findAll()}, so a single authenticated ADMIN GET returned the whole trail
 * at once. Anyone reaching an admin token could exfiltrate every national ID the system had ever
 * touched without going anywhere near the database or the master key.
 *
 * <p>Role enforcement for this path is covered by {@code SecurityFilterChainTest}, which runs the
 * real filter chain; this slice runs with filters disabled and tests what the endpoint returns.
 */
@WebMvcTest(controllers = LineageAuditAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class LineageAuditAdminControllerTest {

    private static final String CITIZEN_TCKN = "12345678950";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LineageAuditLogRepository auditLogRepository;

    private static LineageAuditLog auditRow() {
        return LineageAuditLog.builder()
                .id(UUID.randomUUID())
                .transactionId("tx-audit-1")
                .userId("citizen-1")
                // As Hibernate hands it back: decrypted by TcknAttributeConverter on load.
                .nationalId(CITIZEN_TCKN)
                .action("SUBMIT_LINEAGE_QUERY")
                .ipAddress("203.0.113.7")
                .userAgent("curl/8")
                .timestamp(Instant.now())
                .details("Submitted lineage query for generations depth: 2")
                .build();
    }

    @Test
    @DisplayName("National identity numbers are masked in the response, never returned in full")
    void auditLogs_MaskNationalIds() throws Exception {
        given(auditLogRepository.findAllByOrderByTimestampDesc(any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(auditRow()), PageRequest.of(0, 50), 1));

        String body = mockMvc.perform(get("/api/v1/lineage/admin/audit-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].nationalIdMasked").value("123*****950"))
                .andExpect(jsonPath("$.content[0].userId").value("citizen-1"))
                .andExpect(jsonPath("$.content[0].action").value("SUBMIT_LINEAGE_QUERY"))
                // The entity's own property name must not appear at all - a masked field alongside
                // an unmasked one would be worse than either.
                .andExpect(jsonPath("$.content[0].nationalId").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(CITIZEN_TCKN);
    }

    @Test
    @DisplayName("An unfiltered read is paged and ordered, not an unbounded findAll of the whole trail")
    void unfilteredRead_IsPagedAndOrdered() throws Exception {
        given(auditLogRepository.findAllByOrderByTimestampDesc(any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(auditRow()), PageRequest.of(0, 50), 1));

        mockMvc.perform(get("/api/v1/lineage/admin/audit-logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.totalElements").value(1));

        // The audit table grows by two or more rows per submitted query and is never trimmed, so
        // findAll() is an out-of-memory waiting for a busy week - and one request that hands over
        // the entire trail.
        verify(auditLogRepository, never()).findAll();
        verify(auditLogRepository).findAllByOrderByTimestampDesc(PageRequest.of(0, 50));
    }

    @Test
    @DisplayName("A caller asking for an enormous page gets the server's cap, not the page it asked for")
    void pageSize_IsCappedServerSide() throws Exception {
        given(auditLogRepository.findAllByOrderByTimestampDesc(any(Pageable.class)))
                .willReturn(Page.empty(PageRequest.of(0, 200)));

        mockMvc.perform(get("/api/v1/lineage/admin/audit-logs")
                        .param("size", "100000")
                        .param("page", "-5"))
                .andExpect(status().isOk());

        verify(auditLogRepository).findAllByOrderByTimestampDesc(PageRequest.of(0, 200));
    }

    @Test
    @DisplayName("Filters route to the matching paged finder")
    void filters_RouteToTheMatchingFinder() throws Exception {
        given(auditLogRepository.findByTransactionIdOrderByTimestampDesc(eq("tx-audit-1"), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(auditRow()), PageRequest.of(0, 50), 1));
        given(auditLogRepository.findByUserIdOrderByTimestampDesc(eq("citizen-1"), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(auditRow()), PageRequest.of(0, 50), 1));
        given(auditLogRepository.findAllByOrderByTimestampDesc(any(Pageable.class)))
                .willReturn(Page.empty(PageRequest.of(0, 50)));

        mockMvc.perform(get("/api/v1/lineage/admin/audit-logs").param("transactionId", "tx-audit-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].transactionId").value("tx-audit-1"));

        mockMvc.perform(get("/api/v1/lineage/admin/audit-logs").param("userId", "citizen-1"))
                .andExpect(status().isOk());

        // A blank filter is not a filter; it must not silently become a full-table read under a
        // different code path.
        mockMvc.perform(get("/api/v1/lineage/admin/audit-logs").param("userId", "  "))
                .andExpect(status().isOk());
        verify(auditLogRepository).findAllByOrderByTimestampDesc(any(Pageable.class));
    }
}
