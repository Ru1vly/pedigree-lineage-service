package com.edevlet.lineage.dto;

import com.edevlet.lineage.domain.model.AncestryTree;
import com.edevlet.lineage.domain.model.ProcessingPhase;
import com.edevlet.lineage.domain.model.TaskStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LineageQueryStatusResponse {
    private String transactionId;
    private TaskStatus status;
    private ProcessingPhase currentPhase;
    private String phaseDescription;
    private int progressPercentage;
    private int retryAfterSeconds;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant completedAt;
    private AncestryTree result;
    private String resultDownloadUrl;
    private String errorCode;
    private String errorMessage;
}
