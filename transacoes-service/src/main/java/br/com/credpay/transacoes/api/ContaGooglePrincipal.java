package br.com.credpay.transacoes.api;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

record ContaGooglePrincipal(UUID contaId, OidcUser identidade) implements OidcUser, ContaAutenticada {
    @Override
    public Map<String, Object> getClaims() { return identidade.getClaims(); }

    @Override
    public Map<String, Object> getAttributes() { return identidade.getAttributes(); }

    @Override
    public OidcIdToken getIdToken() { return identidade.getIdToken(); }

    @Override
    public OidcUserInfo getUserInfo() { return identidade.getUserInfo(); }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return AuthorityUtils.createAuthorityList("ROLE_DEMO");
    }

    @Override
    public String getName() { return "google-" + contaId; }

    @Override
    public String toString() { return "ContaGooglePrincipal[contaId=" + contaId + "]"; }
}
