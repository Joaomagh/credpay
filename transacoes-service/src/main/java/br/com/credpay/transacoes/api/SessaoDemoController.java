package br.com.credpay.transacoes.api;

import java.security.Principal;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Profile("sessao-demo")
@RestController
@RequestMapping("/auth")
class SessaoDemoController {

    @GetMapping("/csrf")
    CsrfToken csrf(CsrfToken token) {
        return token;
    }

    @GetMapping("/sessao")
    Sessao sessao(Principal principal, CsrfToken csrf) {
        return new Sessao(ContaDemoPrincipal.idDaConta(principal), principal.getName(), csrf.getToken());
    }

    record Sessao(UUID id, String usuario, String csrf) {
    }
}
