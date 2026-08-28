package com.edevlet.lineage.infrastructure.security;

import com.edevlet.lineage.domain.model.NationalIdentityContext;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.stream.Collectors;

public class CustomJwtAuthenticationToken extends AbstractAuthenticationToken {

    private final NationalIdentityContext identityContext;
    private final Jwt jwt;

    public CustomJwtAuthenticationToken(NationalIdentityContext identityContext) {
        super(extractAuthorities(identityContext.roles()));
        this.identityContext = identityContext;
        this.jwt = null;
        setAuthenticated(true);
    }

    public CustomJwtAuthenticationToken(Jwt jwt, NationalIdentityContext identityContext, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.jwt = jwt;
        this.identityContext = identityContext;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return jwt != null ? jwt.getTokenValue() : "";
    }

    @Override
    public Object getPrincipal() {
        return identityContext.userId();
    }

    public NationalIdentityContext getIdentityContext() {
        return identityContext;
    }

    public Jwt getJwt() {
        return jwt;
    }

    private static Collection<GrantedAuthority> extractAuthorities(Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER"));
        }
        return roles.stream()
                .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }
}
