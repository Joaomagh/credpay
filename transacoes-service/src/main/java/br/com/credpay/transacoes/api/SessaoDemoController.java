package br.com.credpay.transacoes.api;

import java.security.Principal;

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
    Sessao sessao(Principal principal) {
        return new Sessao(principal.getName());
    }

    record Sessao(String usuario) {
    }
}
