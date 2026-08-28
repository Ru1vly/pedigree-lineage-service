package com.edevlet.lineage.web;

import com.edevlet.lineage.domain.exception.LineageNotFoundException;
import com.edevlet.lineage.domain.exception.LineageResultNotReadyException;
import com.edevlet.lineage.domain.exception.UnauthorizedTaskAccessException;
import com.edevlet.lineage.domain.model.AncestryTree;
import com.edevlet.lineage.domain.model.LineageQueryTask;
import com.edevlet.lineage.domain.model.NationalIdentityContext;
import com.edevlet.lineage.domain.model.TaskStatus;
import com.edevlet.lineage.domain.repository.LineageQueryRepository;
import com.edevlet.lineage.infrastructure.security.UserSecurityContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;

@Slf4j
@RestController
@RequestMapping("/api/v1/lineage/documents")
@RequiredArgsConstructor
public class LineageDocumentController {

    private final LineageQueryRepository queryRepository;
    private final ObjectMapper objectMapper;

    @GetMapping("/{documentId}/download")
    public ResponseEntity<byte[]> downloadPedigreeDocument(@PathVariable String documentId) {
        NationalIdentityContext identity = UserSecurityContextHolder.getRequiredContext();

        // documentId is the owning task's transactionId
        LineageQueryTask task = queryRepository.findByTransactionId(documentId)
                .orElseThrow(() -> new LineageNotFoundException(documentId));

        validateDocumentAccess(task, identity, documentId);

        log.info("Downloading certified pedigree document documentId={} for userId={}", documentId, identity.userId());

        AncestryTree ancestryTree = parseResult(task);
        byte[] documentBytes = renderCertificate(documentId, identity, ancestryTree).getBytes(StandardCharsets.UTF_8);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(generateCertificateFilename(documentId))
                .build());

        return ResponseEntity.ok()
                .headers(headers)
                .body(documentBytes);
    }

    private void validateDocumentAccess(LineageQueryTask task, NationalIdentityContext identity, String documentId) {
        if (!task.getUserId().equals(identity.userId()) && !identity.isAdmin()) {
            log.warn("Unauthorized document download attempt on documentId={} by userId={}", documentId, identity.userId());
            throw new UnauthorizedTaskAccessException(identity.userId(), documentId);
        }

        if (task.getStatus() != TaskStatus.COMPLETED || task.getResultPayload() == null) {
            throw new LineageResultNotReadyException(documentId);
        }
    }

    private String generateCertificateFilename(String documentId) {
        int prefixLength = Math.min(8, documentId.length());
        return "soyagaci_belgesi_" + documentId.substring(0, prefixLength) + ".txt";
    }

    private AncestryTree parseResult(LineageQueryTask task) {
        try {
            return objectMapper.readValue(task.getResultPayload(), AncestryTree.class);
        } catch (Exception e) {
            log.error("Failed to parse stored result payload for transactionId={}", task.getTransactionId(), e);
            throw new LineageResultNotReadyException(task.getTransactionId());
        }
    }

    private String renderCertificate(String documentId, NationalIdentityContext identity, AncestryTree tree) {
        StringBuilder body = new StringBuilder();
        body.append("========================================================================================\n");
        body.append("T.C. NUFUS VE VATANDASLIK ISLERI GENEL MUDURLUGU\n");
        body.append("RESMI UST SOY - ALT SOY SOYAGACI BELGESI / CERTIFIED PEDIGREE LINEAGE CERTIFICATE\n");
        body.append("========================================================================================\n");
        body.append("Belge No / Document ID  : ").append(documentId).append('\n');
        body.append("Sorgulayan / Citizen ID : ").append(identity.userId()).append('\n');
        body.append("Dogrulama Kodu / Hash   : ").append(tree.verificationSealHash()).append('\n');
        body.append("----------------------------------------------------------------------------------------\n");
        body.append("[SELF]\n");
        appendPerson(body, tree.rootPerson());

        for (AncestryTree.GenerationNode generation : tree.generations()) {
            body.append('\n').append('[').append(generation.relationLabel()).append("]\n");
            for (AncestryTree.AncestorPerson member : generation.members()) {
                appendPerson(body, member);
            }
        }

        body.append("----------------------------------------------------------------------------------------\n");
        body.append("Bu belge 5070 sayili Elektronik Imza Kanununa uygun olarak guvenli elektronik imza ile uretilmistir.\n");
        body.append("========================================================================================\n");
        return body.toString();
    }

    private void appendPerson(StringBuilder body, AncestryTree.AncestorPerson person) {
        body.append("TCKN: ").append(person.nationalIdMasked())
                .append(" | Ad Soyad: ").append(person.firstName()).append(' ').append(person.lastName())
                .append(" | Dogum: ").append(person.birthDate()).append(" (").append(person.birthPlace()).append(')')
                .append(" | Durum: ").append(person.status())
                .append(" | Iliski: ").append(person.relation())
                .append('\n');
    }
}
