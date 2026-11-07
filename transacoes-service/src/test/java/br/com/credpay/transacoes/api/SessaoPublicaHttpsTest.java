package br.com.credpay.transacoes.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
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
}
