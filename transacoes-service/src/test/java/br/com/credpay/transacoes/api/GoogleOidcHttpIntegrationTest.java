package br.com.credpay.transacoes.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "server.address=127.0.0.1")
@ActiveProfiles({"sessao-demo", "google-login"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class GoogleOidcHttpIntegrationTest {
    private static final ProvedorOidcFixture PROVEDOR = new ProvedorOidcFixture();
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            "postgres@sha256:665dc7b37ac52a17e298511414c21d23ffa4e6a6ef6225b3e5d59c7afb35e994")
            .withDatabaseName("credpay_oidc_http").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void banco(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @LocalServerPort
    private int porta;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private ClientRegistrationRepository registros;

    @BeforeEach
    void registroDoProvedor() { when(registros.findByRegistrationId("google")).thenReturn(PROVEDOR.registro()); }

    @AfterAll
    static void encerrar() { PROVEDOR.close(); }

    @Test
    void duasIdentidadesDevemCriarContasIsoladas_eNovoLoginDevePreservarCliente() throws Exception {
        try (var a = cliente(); var b = cliente(); var novoA = cliente()) {
            var contaA = entrar(a, "sub-a");
            var criado = a.send(pedido("/clientes").header("Content-Type", "application/json")
                    .header("X-CSRF-TOKEN", contaA.path("csrf").asText())
                    .POST(HttpRequest.BodyPublishers.ofString("{\"nome\":\"Cliente Exemplo\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(criado.statusCode()).isEqualTo(201);
            var clienteId = json.readTree(criado.body()).path("id").asText();
            var contaB = entrar(b, "sub-b");
            assertThat(contaB.path("id").asText()).isNotEqualTo(contaA.path("id").asText());
            assertThat(get(b, "/clientes/" + clienteId).statusCode()).isEqualTo(404);
            var listaB = get(b, "/clientes");
            assertThat(listaB.statusCode()).isEqualTo(200);
            assertThat(json.readTree(listaB.body()).path("itens").size()).isZero();

            var retornoA = entrar(novoA, "sub-a");
            assertThat(retornoA.path("id").asText()).isEqualTo(contaA.path("id").asText());
            assertThat(get(novoA, "/clientes/" + clienteId).statusCode()).isEqualTo(200);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM contas", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM identidades_externas", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM contas WHERE senha_hash IS NOT NULL", Integer.class))
                    .isZero();
        }
    }

    private JsonNode entrar(HttpClient cliente, String sujeito) throws Exception {
        PROVEDOR.sujeito(sujeito);
        var inicio = get(cliente, "/auth/externo/google");
        assertThat(inicio.statusCode()).isEqualTo(302);
        var retorno = PROVEDOR.autorizar(inicio.headers().firstValue("Location").orElseThrow());
        var resposta = cliente.send(HttpRequest.newBuilder(URI.create(retorno)).timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(resposta.statusCode()).isEqualTo(302);
        assertThat(resposta.headers().firstValue("Location")).contains("/entrar");
        var sessao = get(cliente, "/auth/sessao");
        assertThat(sessao.statusCode()).isEqualTo(200);
        return json.readTree(sessao.body());
    }

    private HttpClient cliente() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager()).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private HttpResponse<String> get(HttpClient cliente, String caminho) throws Exception {
        return cliente.send(pedido(caminho).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder pedido(String caminho) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + porta + caminho)).timeout(Duration.ofSeconds(10));
    }
}
