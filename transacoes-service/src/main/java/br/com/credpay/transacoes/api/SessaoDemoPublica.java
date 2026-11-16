package br.com.credpay.transacoes.api;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

final class SessaoDemoPublica {

    private static final String INICIO = "credpay.public.loginAt";
    private static final Duration VALIDADE = Duration.ofMinutes(15);

    private final Clock relogio;
    private final byte[] origem;

    SessaoDemoPublica(Clock relogio, String token) {
        validarOrigem(token);
        this.relogio = relogio;
        this.origem = token.getBytes(StandardCharsets.UTF_8);
    }

    static void validarOrigem(String token) {
        if (token == null || token.length() < 43 || token.length() > 128 || !token.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalStateException("Configuração pública de origem inválida.");
        }
    }

    boolean permitir(HttpServletRequest requisicao, HttpServletResponse resposta) {
        resposta.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        if ("GET".equals(requisicao.getMethod()) && "/actuator/health".equals(requisicao.getRequestURI())) {
            return true;
        }
        var headers = Collections.list(requisicao.getHeaders("X-CredPay-Origin"));
        if (!requisicao.isSecure() || headers.size() != 1 || headers.getFirst().length() > 128
                || !MessageDigest.isEqual(origem, headers.getFirst().getBytes(StandardCharsets.UTF_8))) {
            resposta.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return false;
        }
        var autenticacao = SecurityContextHolder.getContext().getAuthentication();
        if (autenticacao != null && autenticacao.isAuthenticated()
                && !(autenticacao instanceof AnonymousAuthenticationToken)) {
            var sessao = requisicao.getSession(false);
            var inicio = inicio(sessao);
            var agora = relogio.instant();
            if (inicio == null || agora.isBefore(inicio) || Duration.between(inicio, agora).compareTo(VALIDADE) >= 0) {
                encerrar(sessao);
                SecurityContextHolder.clearContext();
                limparCookie(resposta);
                resposta.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                return false;
            }
        }
        return true;
    }

    void registrarLogin(HttpServletRequest requisicao) {
        requisicao.getSession().setAttribute(INICIO, relogio.instant());
    }

    void limparCookie(HttpServletResponse resposta) {
        resposta.addHeader(HttpHeaders.SET_COOKIE, ResponseCookie.from("JSESSIONID", "")
                .path("/").maxAge(Duration.ZERO).secure(true).httpOnly(true).sameSite("Lax").build().toString());
    }

    private Instant inicio(HttpSession sessao) {
        try {
            return sessao != null && sessao.getAttribute(INICIO) instanceof Instant instante ? instante : null;
        } catch (IllegalStateException sessaoJaEncerrada) {
            return null;
        }
    }

    private void encerrar(HttpSession sessao) {
        try {
            if (sessao != null) { sessao.invalidate(); }
        } catch (IllegalStateException sessaoJaEncerrada) {
            // Outra requisição já invalidou a sessão; a resposta permanece 401 e exclui o cookie.
        }
    }
}
