package com.edevlet.lineage;

import com.edevlet.lineage.domain.model.AncestryTree;
import com.edevlet.lineage.domain.model.LineageQueryTask;
import com.edevlet.lineage.domain.model.NationalIdentityContext;
import com.edevlet.lineage.domain.model.TaskStatus;
import com.edevlet.lineage.domain.repository.LineageQueryRepository;
import com.edevlet.lineage.infrastructure.security.UserSecurityContextHolder;
import com.edevlet.lineage.web.LineageDocumentController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The certified-document endpoint: the one that serves citizen PII and is the only place the
 * ownership check on a finished result is enforced.
 *
 * <p>It had essentially no coverage - about an eighth of its lines - which is a poor place for a
 * gap. Everything here is behaviour someone could break without any other test noticing: that one
 * citizen cannot download another's pedigree certificate, that an admin can, that an unfinished or
 * unparseable result is refused rather than rendered half-built, and that the rendered document
 * carries masked ancestor identifiers rather than full ones.
 */
@WebMvcTest(controllers = LineageDocumentController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class LineageDocumentControllerTest {

    private static final String TX_ID = "0192f3a1-doc-0001";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private LineageQueryRepository queryRepository;

    @AfterEach
    void tearDown() {
        UserSecurityContextHolder.clear();
    }

    private void authenticateAs(String userId, String... roles) {
        UserSecurityContextHolder.setContext(new NationalIdentityContext(
                userId, "12345678950", Set.of(roles), Set.of("lineage:read"), "203.0.113.7", "curl/8"));
    }

    private LineageQueryTask completedTaskOwnedBy(String userId) throws Exception {
        AncestryTree tree = new AncestryTree(
                new AncestryTree.AncestorPerson("123*****950", "AHMET", "YILMAZ", "MEHMET", "FATMA",
                        LocalDate.of(1985, 4, 12), "ANKARA", "SAĞ", "KENDİSİ"),
                List.of(new AncestryTree.GenerationNode(1, "Ana / Baba (1. Kuşak)", List.of(
                        new AncestryTree.AncestorPerson("382*****102", "MEHMET", "YILMAZ", "MUSTAFA", "AYŞE",
                                LocalDate.of(1958, 8, 20), "KONYA", "SAĞ", "BABA")))),
                2,
                "SHA256-CONFIRMED-SEAL-9021A",
                "/api/v1/lineage/documents/" + TX_ID + "/download");

        return LineageQueryTask.builder()
                .id(UUID.randomUUID())
                .transactionId(TX_ID)
                .userId(userId)
                .status(TaskStatus.COMPLETED)
                .resultPayload(objectMapper.writeValueAsString(tree))
                .build();
    }

    @Test
    @DisplayName("The owning citizen downloads their own certificate as an attachment")
    void owner_DownloadsCertificate() throws Exception {
        authenticateAs("citizen-1", "ROLE_USER");
        given(queryRepository.findByTransactionId(TX_ID)).willReturn(Optional.of(completedTaskOwnedBy("citizen-1")));

        String document = mockMvc.perform(get("/api/v1/lineage/documents/{id}/download", TX_ID))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andReturn().getResponse().getContentAsString();

        assertThat(document)
                .contains("CERTIFIED PEDIGREE LINEAGE CERTIFICATE")
                .contains(TX_ID)
                .contains("SHA256-CONFIRMED-SEAL-9021A")
                .contains("AHMET")
                .contains("MEHMET");
        // Ancestor identifiers are stored masked and must stay masked on the way out.
        assertThat(document).contains("123*****950").doesNotContain("12345678950");
    }

    @Test
    @DisplayName("A different citizen cannot download someone else's certificate")
    void nonOwner_IsRefused() throws Exception {
        authenticateAs("citizen-2", "ROLE_USER");
        given(queryRepository.findByTransactionId(TX_ID)).willReturn(Optional.of(completedTaskOwnedBy("citizen-1")));

        mockMvc.perform(get("/api/v1/lineage/documents/{id}/download", TX_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED_TASK_ACCESS"));
    }

    @Test
    @DisplayName("An admin may download any citizen's certificate")
    void admin_MayDownloadAnyCertificate() throws Exception {
        authenticateAs("operator-1", "ROLE_ADMIN");
        given(queryRepository.findByTransactionId(TX_ID)).willReturn(Optional.of(completedTaskOwnedBy("citizen-1")));

        mockMvc.perform(get("/api/v1/lineage/documents/{id}/download", TX_ID))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("An unknown document id is 404, not a 500 or an empty file")
    void unknownDocument_IsNotFound() throws Exception {
        authenticateAs("citizen-1", "ROLE_USER");
        given(queryRepository.findByTransactionId("nope")).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/lineage/documents/{id}/download", "nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("TASK_NOT_FOUND"));
    }

    @Test
    @DisplayName("A task that has not completed is refused rather than rendered from a missing result")
    void incompleteTask_IsRefused() throws Exception {
        authenticateAs("citizen-1", "ROLE_USER");
        given(queryRepository.findByTransactionId(TX_ID)).willReturn(Optional.of(LineageQueryTask.builder()
                .id(UUID.randomUUID())
                .transactionId(TX_ID)
                .userId("citizen-1")
                .status(TaskStatus.PROCESSING)
                .build()));

        mockMvc.perform(get("/api/v1/lineage/documents/{id}/download", TX_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("RESULT_NOT_READY"));
    }

    @Test
    @DisplayName("A COMPLETED task with an unparseable result payload is refused, not half-rendered")
    void unparseableResult_IsRefused() throws Exception {
        authenticateAs("citizen-1", "ROLE_USER");
        given(queryRepository.findByTransactionId(TX_ID)).willReturn(Optional.of(LineageQueryTask.builder()
                .id(UUID.randomUUID())
                .transactionId(TX_ID)
                .userId("citizen-1")
                .status(TaskStatus.COMPLETED)
                .resultPayload("{ this is not the ancestry tree }")
                .build()));

        mockMvc.perform(get("/api/v1/lineage/documents/{id}/download", TX_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("RESULT_NOT_READY"));
    }

    @Test
    @DisplayName("The ownership check runs before the readiness check, so it cannot be probed for existence")
    void ownershipCheck_PrecedesReadinessCheck() throws Exception {
        authenticateAs("citizen-2", "ROLE_USER");
        given(queryRepository.findByTransactionId(TX_ID)).willReturn(Optional.of(LineageQueryTask.builder()
                .id(UUID.randomUUID())
                .transactionId(TX_ID)
                .userId("citizen-1")
                .status(TaskStatus.PROCESSING)
                .build()));

        // 409 here would tell a stranger that this transaction exists and is still running.
        mockMvc.perform(get("/api/v1/lineage/documents/{id}/download", TX_ID))
                .andExpect(status().isForbidden());
    }
}
