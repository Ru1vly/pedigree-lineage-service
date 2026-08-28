package com.edevlet.lineage.domain.model;

import java.io.Serializable;
import java.util.Set;

public record NationalIdentityContext(
        String userId,
        String nationalId,
        Set<String> roles,
        Set<String> scopes,
        String ipAddress,
        String userAgent
) implements Serializable {
    public boolean isAdmin() {
        return roles != null && (roles.contains("ROLE_ADMIN") || roles.contains("ADMIN"));
    }
}
