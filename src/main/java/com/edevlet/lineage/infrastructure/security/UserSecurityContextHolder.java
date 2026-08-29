package com.edevlet.lineage.infrastructure.security;

import com.edevlet.lineage.domain.model.NationalIdentityContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

public final class UserSecurityContextHolder {

    private UserSecurityContextHolder() {}

    public static Optional<NationalIdentityContext> getContext() {
        SecurityContext context = SecurityContextHolder.getContext();
        if (context == null) {
            return Optional.empty();
        }
        Authentication auth = context.getAuthentication();
        if (auth instanceof CustomJwtAuthenticationToken customAuth) {
            return Optional.of(customAuth.getIdentityContext());
        }
        return Optional.empty();
    }

    public static NationalIdentityContext getRequiredContext() {
        return getContext().orElseThrow(() ->
                new IllegalStateException("No authenticated NationalIdentityContext found in current thread"));
    }

    public static void setContext(NationalIdentityContext identityContext) {
        CustomJwtAuthenticationToken token = new CustomJwtAuthenticationToken(identityContext);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(token);
        SecurityContextHolder.setContext(context);
    }

    public static void clear() {
        SecurityContextHolder.clearContext();
    }
}
