package br.com.credpay.transacoes.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

class SessaoDemoPublicaTest {

    private static final String HEADER_ORIGEM = "X-CredPay-Origin";

    private final String token = UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
    private final Relogio relogio = new Relogio();
    private final SessaoDemoPublica politica = new SessaoDemoPublica(relogio, token);

    @AfterEach
    void limparContexto() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void devePermitirOrigemValidaEmTlsSemCriarSessaoAnonima() {
        var requisicao = requisicao();
        assertThat(politica.permitir(requisicao, new MockHttpServletResponse())).isTrue();
        assertThat(requisicao.getSession(false)).isNull();
    }

    @Test
    void deveRecusarHttpMesmoComHeaderDeProxyForjado() {
        var requisicao = requisicao();
        requisicao.setSecure(false);
        requisicao.addHeader("X-Forwarded-Proto", "https");
        var resposta = new MockHttpServletResponse();
        assertThat(politica.permitir(requisicao, resposta)).isFalse();
        assertThat(resposta.getStatus()).isEqualTo(403);
        assertThat(requisicao.getSession(false)).isNull();
    }

    @Test
    void deveRecusarOrigemAusenteDivergenteOuDuplicadaSemDestruirSessao() {
        for (String caso : new String[] {"ausente", "divergente", "duplicada"}) {
            var requisicao = requisicao();
            var sessao = new MockHttpSession();
            requisicao.setSession(sessao);
            if (caso.equals("ausente")) {
                requisicao.removeHeader(HEADER_ORIGEM);
            } else if (caso.equals("divergente")) {
                requisicao.removeHeader(HEADER_ORIGEM);
                requisicao.addHeader(HEADER_ORIGEM, "valor-ficticio-do-cliente");
            } else {
                requisicao.addHeader(HEADER_ORIGEM, token);
            }
            var resposta = new MockHttpServletResponse();
            assertThat(politica.permitir(requisicao, resposta)).as(caso).isFalse();
            assertThat(resposta.getStatus()).as(caso).isEqualTo(403);
            assertThat(sessao.isInvalid()).isFalse();
            assertThat(resposta.getHeader(HttpHeaders.SET_COOKIE)).isNull();
        }
    }

    @Test
    void consultasNaoDevemProrrogarPrazoAbsolutoDeQuinzeMinutos() {
        var requisicao = autenticada();
        politica.registrarLogin(requisicao);
        relogio.avancar(Duration.ofMinutes(14).plusSeconds(59));
        assertThat(politica.permitir(requisicao, new MockHttpServletResponse())).isTrue();
        relogio.avancar(Duration.ofSeconds(1));
        var sessao = (MockHttpSession) requisicao.getSession(false);
        var resposta = new MockHttpServletResponse();
        assertThat(politica.permitir(requisicao, resposta)).isFalse();
        assertThat(resposta.getStatus()).isEqualTo(401);
        assertThat(sessao.isInvalid()).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        conferirExclusaoSegura(resposta);
    }

    @Test
    void deveRecusarAutenticacaoSemSessaoSemDataOuComDataFutura() {
        for (String caso : new String[] {"sem-sessao", "sem-data", "futura"}) {
            var requisicao = autenticada();
            if (caso.equals("sem-sessao")) {
                requisicao.getSession().invalidate();
                requisicao.setSession(null);
            } else if (caso.equals("futura")) {
                politica.registrarLogin(requisicao);
                relogio.avancar(Duration.ofSeconds(-1));
            }
            var resposta = new MockHttpServletResponse();
            assertThat(politica.permitir(requisicao, resposta)).as(caso).isFalse();
            assertThat(resposta.getStatus()).as(caso).isEqualTo(401);
            conferirExclusaoSegura(resposta);
        }
    }

    @Test
    void exclusaoDeCookieDeveSerSeguraMesmoSemInferirHttpsDaRequisicao() {
        var resposta = new MockHttpServletResponse();
        politica.limparCookie(resposta);
        conferirExclusaoSegura(resposta);
    }

    @Test
    void probePrivadaDevePermitirSomenteHealthSemCriarSessao() {
        var requisicao = new MockHttpServletRequest("GET", "/actuator/health");
        assertThat(politica.permitir(requisicao, new MockHttpServletResponse())).isTrue();
        assertThat(requisicao.getSession(false)).isNull();
        requisicao.setRequestURI("/actuator/metrics");
        var resposta = new MockHttpServletResponse();
        assertThat(politica.permitir(requisicao, resposta)).isFalse();
        assertThat(resposta.getStatus()).isEqualTo(403);
    }

    @Test
    void sessaoInvalidadaPorOutraRequisicaoDeveResponder401() {
        var requisicao = autenticada();
        requisicao.setSession(new MockHttpSession() {
            @Override
            public Object getAttribute(String nome) {
                invalidate();
                return super.getAttribute(nome);
            }
        });
        politica.registrarLogin(requisicao);
        var resposta = new MockHttpServletResponse();
        assertThat(politica.permitir(requisicao, resposta)).isFalse();
        assertThat(resposta.getStatus()).isEqualTo(401);
        conferirExclusaoSegura(resposta);
    }

    @Test
    void configuracaoInvalidaDeveFalharSemExporValor() {
        for (String valor : new String[] {null, "", "curto-ficticio", token + "!", "x".repeat(129)}) {
            assertThatThrownBy(() -> new SessaoDemoPublica(relogio, valor))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Configuração pública de origem inválida.");
        }
    }

    private MockHttpServletRequest requisicao() {
        var requisicao = new MockHttpServletRequest("GET", "/auth/sessao");
        requisicao.setSecure(true);
        requisicao.addHeader(HEADER_ORIGEM, token);
        return requisicao;
    }

    private MockHttpServletRequest autenticada() {
        var requisicao = requisicao();
        requisicao.setSession(new MockHttpSession());
        var conta = new ContaDemoPrincipal(UUID.randomUUID(), "conta-ficticia", "0".repeat(96));
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(conta, null, AuthorityUtils.NO_AUTHORITIES));
        return requisicao;
    }

    private void conferirExclusaoSegura(MockHttpServletResponse resposta) {
        assertThat(resposta.getHeaders(HttpHeaders.SET_COOKIE)).hasSize(1);
        assertThat(resposta.getHeader(HttpHeaders.SET_COOKIE))
                .contains("JSESSIONID=", "Path=/", "Max-Age=0", "Secure", "HttpOnly", "SameSite=Lax")
                .doesNotContain("Domain=");
    }

    private static final class Relogio extends Clock {
        private Instant agora = Instant.parse("2026-11-07T00:00:00Z");

        void avancar(Duration intervalo) { agora = agora.plus(intervalo); }

        @Override
        public Instant instant() { return agora; }

        @Override
        public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override
        public Clock withZone(ZoneId zone) { return Clock.fixed(agora, zone); }
    }
}
