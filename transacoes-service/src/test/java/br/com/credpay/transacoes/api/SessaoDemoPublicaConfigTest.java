package br.com.credpay.transacoes.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SessaoDemoPublicaConfigTest {

    private final String token = UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");
    private final SessaoDemoConfiguration configuracao = new SessaoDemoConfiguration();

    @Test
    void configuracaoValidaDeveVincularOrigemSemCriarSessao() {
        var politica = configuracao.sessaoPublica(Clock.systemUTC(), ambiente());
        var requisicao = new MockHttpServletRequest("GET", "/auth/csrf");
        requisicao.setSecure(true);
        requisicao.addHeader("X-CredPay-Origin", token);
        assertThat(politica.permitir(requisicao, new MockHttpServletResponse())).isTrue();
        assertThat(requisicao.getSession(false)).isNull();
    }

    @Test
    void deveRecusarTransporteOuCookieInseguroSemExporCredencial() {
        var invalidos = Map.ofEntries(
                Map.entry("server.ssl.enabled", "false"),
                Map.entry("server.ssl.key-store-type", "JKS"),
                Map.entry("server.ssl.enabled-protocols", "TLSv1.1"),
                Map.entry("server.servlet.session.cookie.secure", "false"),
                Map.entry("server.servlet.session.cookie.http-only", "false"),
                Map.entry("server.servlet.session.cookie.same-site", "none"),
                Map.entry("server.servlet.session.cookie.path", "/outra"),
                Map.entry("server.servlet.session.cookie.name", "OUTRA"),
                Map.entry("server.servlet.session.tracking-modes", "url"),
                Map.entry("server.servlet.session.cookie.domain", "example.invalid"),
                Map.entry("server.forward-headers-strategy", "framework"),
                Map.entry("management.server.address", "0.0.0.0"),
                Map.entry("management.server.port", "8080"));
        for (var caso : invalidos.entrySet()) {
            var ambiente = ambiente().withProperty(caso.getKey(), caso.getValue());
            assertThatThrownBy(() -> configuracao.sessaoPublica(Clock.systemUTC(), ambiente))
                    .as(caso.getKey()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(caso.getKey()).hasMessageNotContaining(token);
        }
    }

    @Test
    void deveRecusarFonteRemotaOuAusenteDeChavesSemExporSenha() {
        for (String fonte : new String[] {"", "https://keys.example.invalid/server.p12",
                "file://fileserver.invalid/share/server.p12", "file:////fileserver.invalid/share/server.p12",
                "file:/fixture.p12?query=ficticia", "file:/fixture.p12#fragmento", "file:/[invalida"}) {
            var ambiente = ambiente().withProperty("server.ssl.key-store", fonte);
            assertThatThrownBy(() -> configuracao.sessaoPublica(Clock.systemUTC(), ambiente))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("server.ssl.key-store")
                    .hasMessageNotContaining(token);
        }
        var ambiente = ambiente().withProperty("server.ssl.key-store-password", "");
        assertThatThrownBy(() -> configuracao.sessaoPublica(Clock.systemUTC(), ambiente))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("server.ssl.key-store-password")
                .hasMessageNotContaining(token);
    }

    @Test
    void portaAdministrativaInvalidaOuIgualAPublicaDeveSerRecusada() {
        for (String porta : new String[] {"", "-1", "65536", "invalida", "8080"}) {
            var ambiente = ambiente().withProperty("management.server.port", porta);
            assertThatThrownBy(() -> configuracao.sessaoPublica(Clock.systemUTC(), ambiente))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("management.server.port")
                    .hasMessageNotContaining(token);
        }
    }

    @Test
    void fonteRemotaDeveAbortarContextoAntesDeInstanciarConsumidorTls() {
        var ambiente = ambiente().withProperty("server.ssl.key-store", "https://keys.example.invalid/server.p12");
        ambiente.setActiveProfiles("public-demo");
        var consumiu = new AtomicBoolean();
        new ApplicationContextRunner().withInitializer(contexto -> contexto.setEnvironment(ambiente))
                .withUserConfiguration(TransportePublicoConfiguration.class)
                .withBean("fixtureConsumidorTls", Object.class, () -> { consumiu.set(true); return new Object(); })
                .run(contexto -> {
                    assertThat(contexto).hasFailed();
                    assertThat(contexto.getStartupFailure()).hasMessageContaining("server.ssl.key-store");
                    assertThat(consumiu.get()).isFalse();
                });
    }

    private MockEnvironment ambiente() {
        return new MockEnvironment().withProperty("CREDPAY_ORIGIN_TOKEN", token)
                .withProperty("server.port", "8080").withProperty("server.ssl.enabled", "true")
                .withProperty("server.ssl.key-store-type", "PKCS12")
                .withProperty("server.ssl.key-store", "file:/run/secrets/fixture.p12")
                .withProperty("server.ssl.key-store-password", UUID.randomUUID().toString())
                .withProperty("server.ssl.enabled-protocols", "TLSv1.3,TLSv1.2")
                .withProperty("server.servlet.session.cookie.secure", "true")
                .withProperty("server.servlet.session.cookie.http-only", "true")
                .withProperty("server.servlet.session.cookie.same-site", "lax")
                .withProperty("server.servlet.session.cookie.path", "/")
                .withProperty("server.servlet.session.cookie.name", "JSESSIONID")
                .withProperty("server.servlet.session.tracking-modes", "cookie")
                .withProperty("server.forward-headers-strategy", "none")
                .withProperty("management.server.address", "127.0.0.1")
                .withProperty("management.server.port", "9000");
    }
}
