package br.com.credpay.transacoes.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.net.CookieManager;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import tools.jackson.databind.ObjectMapper;
import br.com.credpay.transacoes.application.CadastroContaEmail;
import br.com.credpay.transacoes.domain.PedidoCadastroConta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
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
        properties = "server.address=127.0.0.1")
@ActiveProfiles("sessao-demo")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class SessaoDemoHttpTest {

    @Test
    void contaExternaDeveRecusarSenhaLocalSemAutenticar() throws Exception {
        var id = UUID.randomUUID();
        var usuario = "google-" + id;
        assertThatCode(() -> jdbc.update("INSERT INTO contas (id, usuario, senha_hash) VALUES (?, ?, NULL)",
                id, usuario)).doesNotThrowAnyException();

        try (var cliente = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            assertThat(post(cliente, "/auth/login", formulario(usuario, "senha-ficticia"), csrf(cliente))).isEqualTo(401);
            assertThat(get(cliente, "/auth/sessao").statusCode()).isEqualTo(401);
        }
    }

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            "postgres@sha256:665dc7b37ac52a17e298511414c21d23ffa4e6a6ef6225b3e5d59c7afb35e994")
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
    private CadastroContaEmail cadastroEmail;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private RelogioFixture relogio;

    @BeforeEach
    void iniciarJanelaIsolada() {
        relogio.avancarMinuto();
    }

    @Test
    void cadastroDevePersistirContaVaziaComHashEEmailUnico() {
        var email = "vazia-" + UUID.randomUUID() + "@example.com";
        var senha = "senha de ensaio";
        assertThatCode(() -> cadastroEmail.cadastrar(new PedidoCadastroConta(email, senha))).doesNotThrowAnyException();
        var conta = jdbc.queryForObject("SELECT id FROM contas WHERE email = ?", UUID.class, email);
        var hash = jdbc.queryForObject("SELECT senha_hash FROM contas WHERE id = ?", String.class, conta);
        assertThat(encoder.matches(senha, hash)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM clientes WHERE conta_id = ?", Integer.class, conta)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transacoes WHERE conta_id = ?", Integer.class, conta)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM identidades_externas WHERE conta_id = ?", Integer.class, conta)).isZero();
    }

    @Test
    void cadastroDuplicadoNaoDeveTrocarSenhaOuCriarOutraConta() {
        var email = "repetida-" + UUID.randomUUID() + "@example.com";
        var senha = "primeira senha de ensaio";
        assertThatCode(() -> cadastroEmail.cadastrar(new PedidoCadastroConta(email, senha))).doesNotThrowAnyException();
        var original = jdbc.queryForMap("SELECT id, senha_hash FROM contas WHERE email = ?", email);
        cadastroEmail.cadastrar(new PedidoCadastroConta(email.toUpperCase(), "outra senha de ensaio"));
        assertThat(jdbc.queryForMap("SELECT id, senha_hash FROM contas WHERE email = ?", email)).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM contas WHERE email = ?", Integer.class, email)).isOne();
    }

    @Test
    void cadastroConcorrenteDeveCriarSomenteUmaConta() throws Exception {
        var email = "concorrente-" + UUID.randomUUID() + "@example.com";
        var inicio = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var primeiro = executor.submit(() -> {
                inicio.await();
                cadastroEmail.cadastrar(new PedidoCadastroConta(email, "senha de ensaio A"));
                return true;
            });
            var segundo = executor.submit(() -> {
                inicio.await();
                cadastroEmail.cadastrar(new PedidoCadastroConta(email, "senha de ensaio B"));
                return true;
            });
            inicio.countDown();
            assertThat(primeiro.get(15, TimeUnit.SECONDS)).isTrue();
            assertThat(segundo.get(15, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM contas WHERE email = ?", Integer.class, email)).isOne();
    }

    @Test
    void cadastroSqlDeveExigirEmailCanonicoEUnico() {
        var email = "sql-" + UUID.randomUUID() + "@example.com";
        var sql = "INSERT INTO contas (id, usuario, senha_hash, email) VALUES (?, ?, ?, ?)";
        assertThatCode(() -> jdbc.update(sql, UUID.randomUUID(), "email-" + UUID.randomUUID(), "a".repeat(96), email))
                .doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatExceptionOfType(org.springframework.dao.DataIntegrityViolationException.class)
                .isThrownBy(() -> jdbc.update(sql, UUID.randomUUID(), "email-" + UUID.randomUUID(), "a".repeat(96), email));
        org.assertj.core.api.Assertions.assertThatExceptionOfType(org.springframework.dao.DataIntegrityViolationException.class)
                .isThrownBy(() -> jdbc.update(sql, UUID.randomUUID(), "email-" + UUID.randomUUID(), "a".repeat(96), " Nome@EXAMPLE.COM "));
    }

    @Test
    void cadastroHttpDeveExigirCsrfEPermitirLoginNormalizadoSemAutenticarNoCadastro() throws Exception {
        var email = "pessoa+" + UUID.randomUUID() + "@example.com";
        var senha = "senha de ensaio";
        try (var cliente = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            assertThat(registrarEmail(cliente, email, senha, null).statusCode()).isEqualTo(403);
            var resposta = registrarEmail(cliente, email, senha, csrf(cliente));
            assertThat(resposta.statusCode()).isEqualTo(202);
            assertThat(resposta.body()).isEmpty();
            assertThat(resposta.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(get(cliente, "/auth/sessao").statusCode()).isEqualTo(401);
            assertThat(post(cliente, "/auth/login", formulario(" " + email.toUpperCase() + " ", senha), csrf(cliente))).isEqualTo(204);
            conferirConta(cliente, email);
            assertThat(registrarEmail(cliente, "outra@example.com", senha, csrf(cliente)).statusCode()).isEqualTo(403);
        }
    }

    @Test
    void cadastroHttpDuplicadoDeveTerMesmaRespostaSemSobrescreverCredencial() throws Exception {
        var email = "replay-" + UUID.randomUUID() + "@example.com";
        var senha = "senha de ensaio original";
        try (var cliente = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            var token = csrf(cliente);
            var primeira = registrarEmail(cliente, email, senha, token);
            var repetida = registrarEmail(cliente, email, "outra senha de ensaio", token);
            assertThat(primeira.statusCode()).isEqualTo(202);
            assertThat(repetida.statusCode()).isEqualTo(primeira.statusCode());
            assertThat(repetida.body()).isEqualTo(primeira.body());
            assertThat(post(cliente, "/auth/login", formulario(email, "outra senha de ensaio"), token)).isEqualTo(401);
            assertThat(post(cliente, "/auth/login", formulario(email, senha), csrf(cliente))).isEqualTo(204);
        }
    }

    @Test
    void cadastroHttpDeveLimitarAntesDeCsrfOuHash() throws Exception {
        try (var cliente = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            for (int tentativa = 0; tentativa < 5; tentativa++) {
                assertThat(registrarEmail(cliente, "limite@example.com", "senha de ensaio", null).statusCode()).isEqualTo(403);
            }
            conferirLimite(registrarEmail(cliente, "limite@example.com", "senha de ensaio", null));
        }
    }

    private HttpResponse<String> registrarEmail(HttpClient cliente, String email, String senha, String csrf) throws Exception {
        var pedido = requisicao("/auth/registro").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("email", email, "senha", senha))));
        if (csrf != null) { pedido.header("X-CSRF-TOKEN", csrf); }
        return cliente.send(pedido.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void deveLimitarTokenELoginAntesDaAutenticacao() throws Exception {
        try (var cliente = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            for (int tentativa = 0; tentativa < 30; tentativa++) {
                assertThat(get(cliente, "/auth/csrf").statusCode()).isEqualTo(200);
            }
            conferirLimite(get(cliente, "/auth/csrf"));
            for (int tentativa = 0; tentativa < 20; tentativa++) {
                assertThat(post(cliente, "/auth/login", "", null)).isEqualTo(403);
            }
            var excesso = cliente.send(requisicao("/auth/login").POST(HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.discarding());
            conferirLimite(excesso);
            relogio.avancarMinuto();
            assertThat(get(cliente, "/auth/csrf").statusCode()).isEqualTo(200);
            assertThat(post(cliente, "/auth/login", "", null)).isEqualTo(403);
        }
    }

    @Test
    void deveLimitarCriacaoEConsultaSeparadamentePorConta() throws Exception {
        var senhaA = registrarConta("limite-a");
        var senhaB = registrarConta("limite-b");
        var chave = UUID.randomUUID();
        var consulta = "/transacoes/" + UUID.randomUUID();
        try (var a = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
             var b = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            assertThat(post(a, "/auth/login", formulario("limite-a", senhaA), csrf(a))).isEqualTo(204);
            assertThat(post(b, "/auth/login", formulario("limite-b", senhaB), csrf(b))).isEqualTo(204);
            var csrfA = csrf(a);
            for (int tentativa = 0; tentativa < 45; tentativa++) {
                assertThat(get(a, consulta).statusCode()).isEqualTo(404);
            }
            conferirLimite(get(a, consulta));
            assertThat(get(b, consulta).statusCode()).isEqualTo(404);

            for (int tentativa = 0; tentativa < 12; tentativa++) {
                assertThat(criar(a, chave, "10.00", csrfA).statusCode()).isEqualTo(201);
            }
            conferirLimite(criar(a, chave, "10.00", csrfA));
            assertThat(criar(b, chave, "10.00", csrf(b)).statusCode()).isEqualTo(201);
            relogio.avancarMinuto();
            assertThat(get(a, consulta).statusCode()).isEqualTo(404);
            assertThat(criar(a, chave, "10.00", csrfA).statusCode()).isEqualTo(201);
        }
    }

    private void conferirLimite(HttpResponse<?> resposta) {
        assertThat(resposta.statusCode()).isEqualTo(429);
        assertThat(resposta.headers().firstValue("Retry-After")).contains("60");
    }

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
            var idOutra = jdbc.queryForObject("SELECT id FROM contas WHERE usuario = 'outra-consulta'", UUID.class);
            var requisicaoDeAbaAntiga = requisicao("/transacoes/" + transacao)
                    .header("X-CredPay-Conta", idOutra.toString()).build();
            assertThat(dona.send(requisicaoDeAbaAntiga, HttpResponse.BodyHandlers.discarding()).statusCode())
                    .isEqualTo(401);

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
            var idContaB = jdbc.queryForObject("SELECT id FROM contas WHERE usuario = 'outra-criacao'", UUID.class);
            var escritaDeAbaAntiga = requisicao("/transacoes").header("Content-Type", "application/json")
                    .header("Idempotency-Key", chave.toString()).header("X-CSRF-TOKEN", csrfA)
                    .header("X-CredPay-Conta", idContaB.toString())
                    .POST(HttpRequest.BodyPublishers.ofString("{\"valor\":12.00,\"moeda\":\"BRL\"}")).build();
            assertThat(clienteA.send(escritaDeAbaAntiga, HttpResponse.BodyHandlers.discarding()).statusCode())
                    .isEqualTo(401);
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
            var sessaoA = json.readTree(get(clienteA, "/auth/sessao").body());
            assertThat(sessaoA.path("id").asText()).isEqualTo(jdbc.queryForObject(
                    "SELECT id FROM contas WHERE usuario = 'conta-a'", UUID.class).toString());
            var csrfDepois = sessaoA.path("csrf").asText();
            assertThat(post(clienteB, "/auth/logout", "", csrfDepois)).isEqualTo(403);
            assertThat(post(clienteA, "/auth/logout", "", csrfDepois)).isEqualTo(204);
            assertThat(get(clienteA, "/auth/sessao").statusCode()).isEqualTo(401);
            conferirConta(clienteB, "conta-b");
        }
    }

    @Test
    void clientes_devePersistirEIsolarDuasContas_comSessaoECsrfReais() throws Exception {
        var senhaA = registrarConta("clientes-a");
        var senhaB = registrarConta("clientes-b");
        try (var a = HttpClient.newBuilder().cookieHandler(new CookieManager()).build();
             var b = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            assertThat(post(a, "/auth/login", formulario("clientes-a", senhaA), csrf(a))).isEqualTo(204);
            assertThat(post(b, "/auth/login", formulario("clientes-b", senhaB), csrf(b))).isEqualTo(204);
            var csrfA = csrf(a);
            var csrfB = csrf(b);
            assertThat(cadastrarCliente(a, "{\"nome\":\"Cliente Exemplo\"}", null).statusCode()).isEqualTo(403);
            assertThat(cadastrarCliente(a, "{\"nome\":\"Cliente Exemplo\"}", csrfB).statusCode()).isEqualTo(403);

            var criacao = cadastrarCliente(a, "{\"nome\":\" Cliente Exemplo \"}", csrfA);
            assertThat(criacao.statusCode()).isEqualTo(201);
            var id = json.readTree(criacao.body()).path("id").asText();
            assertThat(criacao.headers().firstValue("Location")).contains("/clientes/" + id);
            var consulta = get(a, "/clientes/" + id);
            assertThat(consulta.statusCode()).isEqualTo(200);
            assertThat(json.readTree(consulta.body()).path("nome").asText()).isEqualTo("Cliente Exemplo");
            assertThat(consulta.headers().firstValue("Cache-Control")).contains("no-store");

            var alheio = get(b, "/clientes/" + id);
            var ausente = get(b, "/clientes/" + UUID.randomUUID());
            assertThat(alheio.statusCode()).isEqualTo(404);
            assertThat(ausente.statusCode()).isEqualTo(404);
            assertThat(json.readTree(alheio.body()).path("detail").asText())
                    .isEqualTo(json.readTree(ausente.body()).path("detail").asText());
            var listaB = get(b, "/clientes");
            assertThat(listaB.statusCode()).isEqualTo(200);
            assertThat(json.readTree(listaB.body()).path("itens").size()).isZero();

            var segunda = cadastrarCliente(a, "{\"nome\":\"Cliente Exemplo\"}", csrfA);
            assertThat(segunda.statusCode()).isEqualTo(201);
            var segundoId = json.readTree(segunda.body()).path("id").asText();
            assertThat(segundoId).isNotEqualTo(id);
            var primeiraPagina = json.readTree(get(a, "/clientes?limite=1").body());
            assertThat(primeiraPagina.path("itens").size()).isEqualTo(1);
            var cursor = primeiraPagina.path("proximo").asText();
            assertThat(cursor).isEqualTo(primeiraPagina.path("itens").get(0).path("id").asText());
            var seguinte = json.readTree(get(a, "/clientes?limite=1&apos=" + cursor).body());
            assertThat(seguinte.path("itens").size()).isEqualTo(1);
            assertThat(seguinte.path("proximo").isNull()).isTrue();
            assertThat(java.util.List.of(primeiraPagina.path("itens").get(0).path("id").asText(),
                    seguinte.path("itens").get(0).path("id").asText())).containsExactlyInAnyOrder(id, segundoId);
        }
    }

    private HttpResponse<String> cadastrarCliente(HttpClient cliente, String corpo, String csrf) throws Exception {
        var pedido = requisicao("/clientes").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(corpo));
        if (csrf != null) { pedido.header("X-CSRF-TOKEN", csrf); }
        return cliente.send(pedido.build(), HttpResponse.BodyHandlers.ofString());
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
        return "usuario=" + URLEncoder.encode(nome, StandardCharsets.UTF_8) + "&senha=" + URLEncoder.encode(senha, StandardCharsets.UTF_8);
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

    @TestConfiguration(proxyBeanMethods = false)
    static class TempoDeTeste {
        @Bean
        @Primary
        @Qualifier("clock")
        RelogioFixture relogioFixture() {
            return new RelogioFixture();
        }
    }

    private static final class RelogioFixture extends Clock {
        private final AtomicReference<Instant> agora =
                new AtomicReference<>(Instant.parse("2026-11-04T12:00:00Z"));

        void avancarMinuto() { agora.updateAndGet(instante -> instante.plusSeconds(60)); }

        @Override
        public Instant instant() { return agora.get(); }

        @Override
        public ZoneId getZone() { return ZoneOffset.UTC; }

        @Override
        public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
    }
}
