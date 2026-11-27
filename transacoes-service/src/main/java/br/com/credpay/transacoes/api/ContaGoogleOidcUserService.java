package br.com.credpay.transacoes.api;

import br.com.credpay.transacoes.application.CadastroContaExterna;
import br.com.credpay.transacoes.domain.IdentidadeExterna;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

final class ContaGoogleOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {
    private final OidcUserService provedor = new OidcUserService();
    private final CadastroContaExterna cadastro;

    ContaGoogleOidcUserService(CadastroContaExterna cadastro) { this.cadastro = cadastro; }

    @Override
    public OidcUser loadUser(OidcUserRequest requisicao) {
        var identidade = provedor.loadUser(requisicao);
        var conta = cadastro.obterOuCriar(new IdentidadeExterna(
                identidade.getIssuer().toString(), identidade.getSubject()));
        if (conta == null) {
            throw new OAuth2AuthenticationException(new OAuth2Error("conta_indisponivel"));
        }
        return new ContaGooglePrincipal(conta, identidade);
    }
}
