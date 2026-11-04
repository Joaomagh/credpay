package br.com.credpay.transacoes.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
        properties = "server.address=127.0.0.1")
@ActiveProfiles("sessao-demo")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class SessaoDemoHttpTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            "postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0")
            .withDatabaseName("credpay_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void configurarBanco(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    private int porta;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private ObjectMapper json;

    @Test
    void consultaDeSessaoDeveExigirAutenticacaoNoPerfilDeContas() throws Exception {
        try (var cliente = HttpClient.newHttpClient()) {
            assertThat(get(cliente, "/auth/sessao").statusCode()).isEqualTo(401);
        }
    }

    @Test
    void loginDeveRenovarSessaoELogoutDeveInvalidarApenasAContaAtual() throws Exception {
        var senhaA = registrarConta("conta-a");
        var senhaB = registrarConta("conta-b");
        var cookiesA = new CookieManager();
        var cookiesB = new CookieManager();
        try (var clienteA = HttpClient.newBuilder().cookieHandler(cookiesA).build();
             var clienteB = HttpClient.newBuilder().cookieHandler(cookiesB).build()) {
            var formularioA = formulario("conta-a", senhaA);
            assertThat(post(clienteA, "/auth/login", formularioA, null)).isEqualTo(403);
            var csrfAntes = csrf(clienteA);
            var idAntes = idDaSessao(cookiesA);

            assertThat(post(clienteA, "/auth/login", formulario("conta-a", senhaB), csrfAntes)).isEqualTo(401);
            assertThat(post(clienteA, "/auth/login", formulario("conta-ausente", senhaB), csrfAntes)).isEqualTo(401);
            assertThat(post(clienteA, "/auth/login?teste=1", formularioA, csrfAntes)).isEqualTo(400);
            assertThat(get(clienteA, "/auth/sessao").statusCode()).isEqualTo(401);
            assertThat(post(clienteA, "/auth/login", formularioA, csrfAntes)).isEqualTo(204);
            assertThat(idAntes.equals(idDaSessao(cookiesA))).isFalse();
            conferirConta(clienteA, "conta-a");

            assertThat(post(clienteB, "/auth/login", formulario("conta-b", senhaB), csrf(clienteB))).isEqualTo(204);
            conferirConta(clienteB, "conta-b");
            var transacaoLegada = UUID.randomUUID();
            jdbc.update("INSERT INTO transacoes (id, valor, moeda, status) VALUES (?, 10.00, 'BRL', 'PENDENTE')",
                    transacaoLegada);
            assertThat(get(clienteA, "/transacoes/" + transacaoLegada).statusCode()).isEqualTo(404);
            assertThat(post(clienteA, "/auth/logout", "", csrfAntes)).isEqualTo(403);
            var csrfDepois = csrf(clienteA);
            assertThat(post(clienteA, "/transacoes", "", csrfDepois)).isEqualTo(403);
            assertThat(post(clienteA, "/auth/logout", "", csrfDepois)).isEqualTo(204);
            assertThat(get(clienteA, "/auth/sessao").statusCode()).isEqualTo(401);
            conferirConta(clienteB, "conta-b");
        }
    }

    private String registrarConta(String nome) {
        var senhaFicticia = UUID.randomUUID() + " +";
        jdbc.update("INSERT INTO contas (id, usuario, senha_hash) VALUES (?, ?, ?)",
                UUID.randomUUID(), nome, encoder.encode(senhaFicticia));
        return senhaFicticia;
    }

    private String csrf(HttpClient cliente) throws Exception {
        var resposta = get(cliente, "/auth/csrf");
        assertThat(resposta.statusCode()).isEqualTo(200);
        return json.readTree(resposta.body()).path("token").asText();
    }

    private String idDaSessao(CookieManager cookies) {
        return cookies.getCookieStore().getCookies().stream().filter(cookie -> "JSESSIONID".equals(cookie.getName()))
                .findFirst().map(HttpCookie::getValue).orElseThrow();
    }

    private void conferirConta(HttpClient cliente, String nome) throws Exception {
        var resposta = get(cliente, "/auth/sessao");
        assertThat(resposta.statusCode()).isEqualTo(200);
        assertThat(json.readTree(resposta.body()).path("usuario").asText()).isEqualTo(nome);
    }

    private String formulario(String nome, String senha) {
        return "usuario=" + nome + "&senha=" + URLEncoder.encode(senha, StandardCharsets.UTF_8);
    }

    private HttpResponse<String> get(HttpClient cliente, String caminho) throws Exception {
        return cliente.send(requisicao(caminho).build(), HttpResponse.BodyHandlers.ofString());
    }

    private int post(HttpClient cliente, String caminho, String corpo, String csrf) throws Exception {
        var builder = requisicao(caminho).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(corpo));
        if (csrf != null) {
            builder.header("X-CSRF-TOKEN", csrf);
        }
        return cliente.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private HttpRequest.Builder requisicao(String caminho) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + porta + caminho))
                .timeout(Duration.ofSeconds(10));
    }
}
