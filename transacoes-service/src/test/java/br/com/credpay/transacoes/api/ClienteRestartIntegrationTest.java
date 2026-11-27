package br.com.credpay.transacoes.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import br.com.credpay.transacoes.TransacoesServiceApplication;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class ClienteRestartIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            "postgres@sha256:665dc7b37ac52a17e298511414c21d23ffa4e6a6ef6225b3e5d59c7afb35e994")
            .withDatabaseName("credpay_clientes_restart").withUsername("test").withPassword("test");

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void cliente_deveContinuarConsultavel_aposEncerrarEReiniciarAplicacao() throws Exception {
        var usuario = "reinicio-" + UUID.randomUUID();
        var senha = UUID.randomUUID().toString();
        String clienteId;
        try (var primeira = iniciar(); var cliente = cliente()) {
            var jdbc = primeira.getBean(JdbcTemplate.class);
            var hash = primeira.getBean(PasswordEncoder.class).encode(senha);
            jdbc.update("INSERT INTO contas (id, usuario, senha_hash) VALUES (?, ?, ?)", UUID.randomUUID(), usuario, hash);
            var origem = origem(primeira);
            entrar(cliente, origem, usuario, senha);
            var token = token(cliente, origem);
            var resposta = cliente.send(pedido(origem, "/clientes").header("X-CSRF-TOKEN", token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"nome\":\"Cliente Persistente\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(resposta.statusCode()).isEqualTo(201);
            clienteId = json.readTree(resposta.body()).path("id").asText();
        }
        try (var segunda = iniciar(); var cliente = cliente()) {
            var origem = origem(segunda);
            entrar(cliente, origem, usuario, senha);
            var resposta = cliente.send(pedido(origem, "/clientes/" + clienteId).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(resposta.statusCode()).isEqualTo(200);
            assertThat(json.readTree(resposta.body()).path("nome").asText()).isEqualTo("Cliente Persistente");
        }
    }

    private ConfigurableApplicationContext iniciar() {
        return new SpringApplicationBuilder(TransacoesServiceApplication.class).run(
                "--spring.profiles.active=sessao-demo", "--server.address=127.0.0.1", "--server.port=0",
                "--management.health.rabbit.enabled=false", "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword());
    }

    private URI origem(ConfigurableApplicationContext app) {
        return URI.create("http://127.0.0.1:" + app.getEnvironment().getRequiredProperty("local.server.port"));
    }

    private HttpClient cliente() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager()).connectTimeout(Duration.ofSeconds(5)).build();
    }

    private HttpRequest.Builder pedido(URI origem, String caminho) {
        return HttpRequest.newBuilder(origem.resolve(caminho)).timeout(Duration.ofSeconds(10));
    }

    private String token(HttpClient cliente, URI origem) throws Exception {
        var resposta = cliente.send(pedido(origem, "/auth/csrf").GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(resposta.statusCode()).isEqualTo(200);
        return json.readTree(resposta.body()).path("token").asText();
    }

    private void entrar(HttpClient cliente, URI origem, String usuario, String senha) throws Exception {
        var corpo = "usuario=" + usuario + "&senha=" + URLEncoder.encode(senha, StandardCharsets.UTF_8);
        var resposta = cliente.send(pedido(origem, "/auth/login").header("X-CSRF-TOKEN", token(cliente, origem))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(corpo)).build(), HttpResponse.BodyHandlers.discarding());
        assertThat(resposta.statusCode()).isEqualTo(204);
    }
}
