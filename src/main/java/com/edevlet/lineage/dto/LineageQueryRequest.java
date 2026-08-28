package com.edevlet.lineage.dto;

import com.edevlet.lineage.infrastructure.util.ValidTckn;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LineageQueryRequest {

    @NotBlank(message = "National Identity Number (TCKN) is required")
    @ValidTckn
    private String nationalId;

    @Min(value = 1, message = "Generations depth must be at least 1")
    @Max(value = 10, message = "Generations depth cannot exceed 10")
    @Builder.Default
    private int generationsDepth = 3;

    @Builder.Default
    private boolean includeCertificates = false;

    @Pattern(regexp = "^(PDF|JSON|XML)$", message = "Document format must be PDF, JSON, or XML")
    @Builder.Default
    private String documentFormat = "PDF";

    @NotBlank(message = "Idempotency key is required for async request submission")
    private String idempotencyKey;
}
