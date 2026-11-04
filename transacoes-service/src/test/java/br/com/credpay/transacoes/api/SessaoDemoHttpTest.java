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
            assertThat(get(cliente, "/transacoes/" + UUID.randomUUID()).statusCode()).isEqualTo(401);
        }
    }

    @Test
    void consultaDeTransacaoDeveDistinguirApenasODono() throws Exception {
        var senhaDona = registrarConta("dona-consulta");
        var senhaOutra = registrarConta("outra-consulta");
        var transacao = UUID.randomUUID();
        var ausente = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO transacoes (id, valor, moeda, status, conta_id)
                SELECT ?, 10.00, 'BRL', 'PENDENTE', id FROM contas WHERE usuario = 'dona-consulta'
                """, transacao);

        try (var dona = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
             var outra = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            assertThat(post(dona, "/auth/login", formulario("dona-consulta", senhaDona), csrf(dona)))
                    .isEqualTo(204);
            assertThat(post(outra, "/auth/login", formulario("outra-consulta", senhaOutra), csrf(outra)))
                    .isEqualTo(204);

            var propria = get(dona, "/transacoes/" + transacao);
            assertThat(propria.statusCode()).isEqualTo(200);
            assertThat(json.readTree(propria.body()).path("id").asText()).isEqualTo(transacao.toString());
            assertThat(propria.headers().firstValue("Cache-Control")).hasValueSatisfying(
                    valor -> assertThat(valor).contains("no-store"));

            var alheia = get(outra, "/transacoes/" + transacao);
            var inexistente = get(outra, "/transacoes/" + ausente);
            assertThat(alheia.statusCode()).isEqualTo(404);
            assertThat(inexistente.statusCode()).isEqualTo(404);
            assertThat(alheia.body()).isEqualTo(inexistente.body().replace(ausente.toString(), transacao.toString()));
        }
    }

    @Test
    void criacaoDeveRepetirSomenteOReciboDaMesmaConta() throws Exception {
        var senhaA = registrarConta("dona-criacao");
        var senhaB = registrarConta("outra-criacao");
        var chave = UUID.randomUUID();
        try (var clienteA = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
             var clienteB = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            assertThat(post(clienteA, "/auth/login", formulario("dona-criacao", senhaA), csrf(clienteA)))
                    .isEqualTo(204);
            assertThat(post(clienteB, "/auth/login", formulario("outra-criacao", senhaB), csrf(clienteB)))
                    .isEqualTo(204);
            var csrfA = csrf(clienteA);
            var csrfB = csrf(clienteB);
            assertThat(criar(clienteB, chave, "11.00", csrfA).statusCode()).isEqualTo(403);
            var envio1 = clienteA.sendAsync(pedido(chave, "10.00", csrfA), HttpResponse.BodyHandlers.ofString());
            var envio2 = clienteA.sendAsync(pedido(chave, "10.00", csrfA), HttpResponse.BodyHandlers.ofString());
            var primeira = envio1.join();
            var simultanea = envio2.join();
            assertThat(primeira.statusCode()).isEqualTo(201);
            assertThat(simultanea.statusCode()).isEqualTo(201);
            assertThat(simultanea.body()).isEqualTo(primeira.body());
            var replay = criar(clienteA, chave, "10.0", csrfA);
            assertThat(replay.statusCode()).isEqualTo(201);
            assertThat(replay.body()).isEqualTo(primeira.body());
            assertThat(criar(clienteA, chave, "11.00", csrfA).statusCode()).isEqualTo(409);

            var outra = criar(clienteB, chave, "11.00", csrfB);
            assertThat(outra.statusCode()).isEqualTo(201);
            var caminhoA = primeira.headers().firstValue("Location").orElseThrow();
            var caminhoB = outra.headers().firstValue("Location").orElseThrow();
            assertThat(caminhoB).isNotEqualTo(caminhoA);
            var transacaoA = UUID.fromString(json.readTree(primeira.body()).path("id").asText());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_eventos WHERE aggregate_id = ?",
                    Integer.class, transacaoA)).isEqualTo(1);
            assertThat(get(clienteA, caminhoA).statusCode()).isEqualTo(200);
            assertThat(get(clienteB, caminhoB).statusCode()).isEqualTo(200);
            assertThat(get(clienteA, caminhoB).statusCode()).isEqualTo(404);
            assertThat(get(clienteB, caminhoA).statusCode()).isEqualTo(404);
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

    private HttpResponse<String> criar(HttpClient cliente, UUID chave, String valor, String csrf) throws Exception {
        return cliente.send(pedido(chave, valor, csrf), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest pedido(UUID chave, String valor, String csrf) {
        return requisicao("/transacoes")
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", chave.toString())
                .header("X-CSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString("{\"valor\":%s,\"moeda\":\"BRL\"}".formatted(valor)))
                .build();
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
