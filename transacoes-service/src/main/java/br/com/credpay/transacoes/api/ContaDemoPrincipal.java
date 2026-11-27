package br.com.credpay.transacoes.api;

import java.security.Principal;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.userdetails.User;
import org.springframework.web.server.ResponseStatusException;

final class ContaDemoPrincipal extends User implements ContaAutenticada {

    private static final long serialVersionUID = 1L;

    private final UUID contaId;

    ContaDemoPrincipal(UUID contaId, String usuario, String senhaHash) {
        super(usuario, senhaHash, AuthorityUtils.createAuthorityList("ROLE_DEMO"));
        this.contaId = contaId;
    }

    @Override
    public UUID contaId() { return contaId; }

    static UUID idDaConta(Principal principal) {
        if (principal == null) {
            return null;
        }
        if (principal instanceof Authentication autenticacao
                && autenticacao.isAuthenticated() && autenticacao.getPrincipal() instanceof ContaAutenticada conta) {
            return conta.contaId();
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }
}
