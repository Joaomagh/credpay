package br.com.credpay.transacoes.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLHandshakeException;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "management.health.rabbit.enabled=false"})
@ActiveProfiles("public-demo")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class SessaoPublicaHttpsTest {

    private static final TlsPublicaFixture TLS = TlsPublicaFixture.criar();
    private static final String ORIGEM = UUID.randomUUID().toString().replace("-", "")
            + UUID.randomUUID().toString().replace("-", "");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            "postgres@sha256:665dc7b37ac52a17e298511414c21d23ffa4e6a6ef6225b3e5d59c7afb35e994")
            .withDatabaseName("credpay_tls_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void configurar(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("server.ssl.key-store", TLS::uri);
        registry.add("server.ssl.key-store-password", TLS::senha);
        registry.add("server.ssl.key-store-type", () -> "PKCS12");
        registry.add("CREDPAY_ORIGIN_TOKEN", () -> ORIGEM);
    }

    @LocalServerPort
    private int porta;

    @LocalManagementPort
    private int management;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private Relogio relogio;

    @BeforeEach
    void iniciarJanelaIsolada() {
        relogio.avancar(Duration.ofHours(1));
    }

    @Test
    void origemAusenteDeveSerRecusadaEmHttpsAntesDeCriarSessao() throws Exception {
        try (var cliente = HttpClient.newBuilder().sslContext(TLS.confianca())
                .connectTimeout(Duration.ofSeconds(5)).build()) {
            var pedido = HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + porta + "/auth/csrf"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            var resposta = cliente.send(pedido, HttpResponse.BodyHandlers.ofString());
            assertThat(resposta.statusCode()).isEqualTo(403);
            assertThat(resposta.headers().allValues("Set-Cookie")).isEmpty();
            assertThat(resposta.body()).doesNotContain(ORIGEM);
        }
    }

    @Test
    void origemDivergenteOuDuplicadaNaoDeveCriarSessao() throws Exception {
        try (var cliente = cliente(new CookieManager())) {
            for (String caso : new String[] {"divergente", "duplicada"}) {
                var pedido = requisicao("/auth/csrf");
                if (caso.equals("divergente")) {
                    pedido.setHeader("X-CredPay-Origin", ORIGEM + "x");
                } else {
                    pedido.header("X-CredPay-Origin", ORIGEM);
                }
                var resposta = cliente.send(pedido.GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(resposta.statusCode()).as(caso).isEqualTo(403);
                assertThat(resposta.headers().allValues("Set-Cookie")).isEmpty();
                assertThat(resposta.body()).doesNotContain(ORIGEM);
            }
        }
    }

    @Test
    void loginHttpsDeveExpirarAosQuinzeMinutosMesmoComConsultaAnterior() throws Exception {
        var cookies = new CookieManager();
        try (var cliente = cliente(cookies)) {
            var conta = cadastrar();
            entrar(cliente, conta);
            String cookieOriginal = cookies.getCookieStore().getCookies().stream()
                    .filter(cookie -> cookie.getName().equals("JSESSIONID")).findFirst().orElseThrow().toString();
            relogio.avancar(Duration.ofMinutes(14).plusSeconds(59));
            var antes = get(cliente, "/auth/sessao");
            assertThat(antes.statusCode()).isEqualTo(200);
            assertThat(json.readTree(antes.body()).path("id").asText()).isEqualTo(conta.id().toString());
            relogio.avancar(Duration.ofSeconds(1));
            var expirada = get(cliente, "/auth/sessao");
            assertThat(expirada.statusCode()).isEqualTo(401);
            conferirExclusao(expirada);
            assertThat(cookies.getCookieStore().getCookies()).noneMatch(cookie -> cookie.getName().equals("JSESSIONID"));
            var replay = cliente.send(requisicao("/auth/sessao").header("Cookie", cookieOriginal).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(replay.statusCode()).isEqualTo(401);
        }
    }

    @Test
    void logoutHttpsDeveExcluirCookieHostOnlyEInvalidarAutenticacao() throws Exception {
        try (var cliente = cliente(new CookieManager())) {
            entrar(cliente, cadastrar());
            var sessao = get(cliente, "/auth/sessao");
            var csrf = json.readTree(sessao.body()).path("csrf").asText();
            var resposta = cliente.send(requisicao("/auth/logout").header("X-CSRF-TOKEN", csrf)
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(resposta.statusCode()).isEqualTo(204);
            conferirExclusao(resposta);
            assertThat(get(cliente, "/auth/sessao").statusCode()).isEqualTo(401);
        }
    }

    @Test
    void clienteSemConfiancaNaFixtureDeveRecusarCertificado() throws Exception {
        try (var cliente = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            assertThatThrownBy(() -> cliente.send(requisicao("/auth/csrf").GET().build(),
                    HttpResponse.BodyHandlers.ofString())).isInstanceOf(SSLHandshakeException.class);
        }
    }

    @Test
    void healthDeveEstarSomenteNaPortaPrivadaSemDetalhesOuCookie() throws Exception {
        try (var cliente = cliente(new CookieManager())) {
            assertThat(get(cliente, "/actuator/health").statusCode()).isIn(401, 403, 404);
            var pedido = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + management + "/actuator/health"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            var resposta = cliente.send(pedido, HttpResponse.BodyHandlers.ofString());
            assertThat(resposta.statusCode()).isEqualTo(200);
            var health = json.readTree(resposta.body());
            assertThat(health.path("status").asText()).isEqualTo("UP");
            assertThat(health.has("components")).isFalse();
            assertThat(resposta.headers().allValues("Set-Cookie")).isEmpty();
        }
    }

    private HttpClient cliente(CookieManager cookies) throws Exception {
        return HttpClient.newBuilder().sslContext(TLS.confianca()).cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5)).build();
    }

    private HttpRequest.Builder requisicao(String caminho) {
        return HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + porta + caminho))
                .header("X-CredPay-Origin", ORIGEM).timeout(Duration.ofSeconds(5));
    }

    private HttpResponse<String> get(HttpClient cliente, String caminho) throws Exception {
        return cliente.send(requisicao(caminho).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private Acesso cadastrar() {
        var acesso = new Acesso(UUID.randomUUID(), "public-" + UUID.randomUUID(), UUID.randomUUID().toString());
        jdbc.update("INSERT INTO contas (id, usuario, senha_hash) VALUES (?, ?, ?)",
                acesso.id(), acesso.usuario(), encoder.encode(acesso.senha()));
        return acesso;
    }

    private void entrar(HttpClient cliente, Acesso conta) throws Exception {
        var token = get(cliente, "/auth/csrf");
        assertThat(token.statusCode()).isEqualTo(200);
        assertThat(token.sslSession().orElseThrow().getProtocol()).isIn("TLSv1.2", "TLSv1.3");
        assertThat(token.headers().allValues("Set-Cookie")).hasSize(1);
        conferirCookieSeguro(token.headers().firstValue("Set-Cookie").orElseThrow());
        var csrf = json.readTree(token.body()).path("token").asText();
        String corpo = "usuario=" + URLEncoder.encode(conta.usuario(), StandardCharsets.UTF_8)
                + "&senha=" + URLEncoder.encode(conta.senha(), StandardCharsets.UTF_8);
        var resposta = cliente.send(requisicao("/auth/login").header("X-CSRF-TOKEN", csrf)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(corpo)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(resposta.statusCode()).isEqualTo(204);
        conferirCookieSeguro(resposta.headers().firstValue("Set-Cookie").orElseThrow());
        assertThat(resposta.body()).doesNotContain(ORIGEM, conta.senha());
    }

    private void conferirCookieSeguro(String cookie) {
        assertThat(cookie).contains("Secure", "HttpOnly", "SameSite=Lax", "Path=/").doesNotContain("Domain=");
    }

    private void conferirExclusao(HttpResponse<String> resposta) {
        assertThat(resposta.headers().allValues("Set-Cookie")).hasSize(1);
        var cookie = resposta.headers().firstValue("Set-Cookie").orElseThrow();
        conferirCookieSeguro(cookie);
        assertThat(cookie).contains("JSESSIONID=", "Max-Age=0");
    }

    private record Acesso(UUID id, String usuario, String senha) { }

    @TestConfiguration
    static class ConfiguracaoRelogio {
        @Bean
        @Primary
        Relogio relogioPublicoFixture() { return new Relogio(); }
    }

    private static final class Relogio extends Clock {
        private final AtomicReference<Instant> agora = new AtomicReference<>(Instant.parse("2026-11-07T00:00:00Z"));

        void avancar(Duration intervalo) { agora.updateAndGet(instante -> instante.plus(intervalo)); }

        @Override
        public Instant instant() { return agora.get(); }

        @Override
        public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override
        public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
    }
}
