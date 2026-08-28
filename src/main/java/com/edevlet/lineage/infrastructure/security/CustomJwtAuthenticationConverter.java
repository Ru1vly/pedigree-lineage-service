package com.edevlet.lineage.infrastructure.security;

import com.edevlet.lineage.domain.model.NationalIdentityContext;
import com.edevlet.lineage.infrastructure.tracing.TracingMdcFilter;
import com.edevlet.lineage.infrastructure.util.TcknValidator;
import org.slf4j.MDC;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

@Component
public class CustomJwtAuthenticationConverter implements Converter<Jwt, CustomJwtAuthenticationToken> {

    @Override
    public CustomJwtAuthenticationToken convert(Jwt jwt) {
        String userId = extractUserId(jwt);
        String nationalId = extractNationalId(jwt);

        // Populate MDC userId once authentication succeeds
        MDC.put(TracingMdcFilter.MDC_USER_ID, userId);

        Set<String> roles = extractRoles(jwt);
        Set<String> scopes = extractScopes(jwt);
        Collection<GrantedAuthority> authorities = buildGrantedAuthorities(roles, scopes);

        NationalIdentityContext identityContext = new NationalIdentityContext(
                userId,
                nationalId,
                roles,
                scopes,
                null,
                null
        );

        return new CustomJwtAuthenticationToken(jwt, identityContext, authorities);
    }

    private String extractUserId(Jwt jwt) {
        String userId = jwt.getClaimAsString("sub");
        if (userId != null && !userId.isBlank()) {
            return userId;
        }
        return jwt.getSubject();
    }

    private String extractNationalId(Jwt jwt) {
        String nationalId = jwt.getClaimAsString("national_id");
        if (nationalId == null) {
            nationalId = jwt.getClaimAsString("tc_no");
        }
        if (nationalId == null) {
            nationalId = jwt.getClaimAsString("tckn");
        }

        // A token without a valid national ID must be rejected. Downstream ownership checks,
        // audit logs, and encrypted columns depend on this identity claim.
        if (nationalId == null || nationalId.isBlank()) {
            throw new InvalidBearerTokenException(
                    "Token is missing a national identity claim (national_id, tc_no or tckn)");
        }
        if (!TcknValidator.isValid(nationalId)) {
            throw new InvalidBearerTokenException("Token carries a malformed national identity claim");
        }
        return nationalId;
    }

    private Collection<GrantedAuthority> buildGrantedAuthorities(Set<String> roles, Set<String> scopes) {
        Collection<GrantedAuthority> authorities = new ArrayList<>();
        for (String role : roles) {
            String roleName = role.startsWith("ROLE_") ? role : "ROLE_" + role;
            authorities.add(new SimpleGrantedAuthority(roleName));
        }
        for (String scope : scopes) {
            authorities.add(new SimpleGrantedAuthority("SCOPE_" + scope));
        }
        return authorities;
    }

    @SuppressWarnings("unchecked")
    private Set<String> extractRoles(Jwt jwt) {
        Set<String> roles = new HashSet<>();

        Map<String, Object> realmAccess = jwt.getClaim("realm_access");
        if (realmAccess != null && realmAccess.get("roles") instanceof List<?> realmRoles) {
            for (Object roleObj : realmRoles) {
                if (roleObj instanceof String role) {
                    roles.add(role);
                }
            }
        }

        List<String> directRoles = jwt.getClaimAsStringList("roles");
        if (directRoles != null) {
            roles.addAll(directRoles);
        }

        if (roles.isEmpty()) {
            roles.add("USER");
        }

        return roles;
    }

    private Set<String> extractScopes(Jwt jwt) {
        List<String> scopeList = jwt.getClaimAsStringList("scope");
        if (scopeList != null) {
            return new HashSet<>(scopeList);
        }
        String scopeString = jwt.getClaimAsString("scope");
        if (scopeString != null && !scopeString.isBlank()) {
            return Arrays.stream(scopeString.split(" "))
                    .collect(Collectors.toSet());
        }
        return Collections.emptySet();
    }
}
