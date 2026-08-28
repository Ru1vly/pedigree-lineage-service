package com.edevlet.lineage.infrastructure.messaging;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LineageQueryMessage implements Serializable {
    private String transactionId;
    private String userId;
    private String nationalId;
    private int generationsDepth;
    private boolean includeCertificates;
    private String documentFormat;
    private String idempotencyKey;
    private String traceId;
    private Instant submittedAt;
}
