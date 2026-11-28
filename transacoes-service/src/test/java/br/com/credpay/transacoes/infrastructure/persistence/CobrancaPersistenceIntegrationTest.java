package br.com.credpay.transacoes.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import br.com.credpay.transacoes.application.Cobrancas;
import br.com.credpay.transacoes.application.ClienteNaoEncontradoException;
import br.com.credpay.transacoes.application.ConflitoIdempotenciaException;
import br.com.credpay.transacoes.domain.Cobranca;
import br.com.credpay.transacoes.domain.PedidoCobranca;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.jdbc.template.query-timeout=5s")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class CobrancaPersistenceIntegrationTest {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            "postgres@sha256:665dc7b37ac52a17e298511414c21d23ffa4e6a6ef6225b3e5d59c7afb35e994")
            .withDatabaseName("credpay_cobrancas").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void banco(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private Cobrancas service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transacoes;
    @MockitoBean(name = "clock")
    private Clock relogio;
    private UUID conta;
    private UUID outra;
    private UUID cliente;
    private UUID alheio;

    @BeforeEach
    void preparar() {
        when(relogio.instant()).thenReturn(Instant.parse("2026-11-27T18:00:00.123456789Z"));
        conta = conta();
        outra = conta();
        cliente = cliente(conta);
        alheio = cliente(outra);
    }

    @Test
    void criar_deveComitarValorExatoEVinculoSemGerarTransacaoFicticia() {
        assertThatCode(() -> {
            var chave = UUID.randomUUID();
            var cobranca = service.criar(conta, chave, pedido(cliente));
            assertThat(jdbc.queryForObject("SELECT id FROM cobrancas WHERE conta_id = ? AND chave = ?",
                    UUID.class, conta, chave)).isEqualTo(cobranca.id());
            assertThat(jdbc.queryForObject("SELECT valor FROM cobrancas WHERE id = ?", BigDecimal.class, cobranca.id()))
                    .isEqualByComparingTo("250.00");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM transacoes", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_eventos", Integer.class)).isZero();
        }).doesNotThrowAnyException();
    }

    @Test
    void repetir_deveRetornarRegistroPersistidoInclusiveInstanteOriginal() {
        var chave = UUID.randomUUID();
        var original = service.criar(conta, chave, pedido(cliente));
        assertThat(service.criar(conta, chave, pedido(cliente))).isEqualTo(original);
        assertThatCode(() -> assertThat(contagem()).isEqualTo(1)).doesNotThrowAnyException();
    }

    @Test
    void conflito_devePreservarRegistroOriginal() {
        var chave = UUID.randomUUID();
        var original = service.criar(conta, chave, pedido(cliente));
        var diferente = new PedidoCobranca(cliente, "Outro serviço", BigDecimal.TEN, "BRL", LocalDate.of(2026, 12, 10));
        assertThatThrownBy(() -> service.criar(conta, chave, diferente)).isInstanceOf(ConflitoIdempotenciaException.class);
        assertThat(service.criar(conta, chave, pedido(cliente))).isEqualTo(original);
    }

    @Test
    void mesmaChave_deveSerIndependentePorConta() {
        var chave = UUID.randomUUID();
        var a = service.criar(conta, chave, pedido(cliente));
        var b = service.criar(outra, chave, pedido(alheio));
        assertThat(a.id()).isNotEqualTo(b.id());
        assertThatCode(() -> assertThat(contagem()).isEqualTo(1)).doesNotThrowAnyException();
    }

    @Test
    void clienteAlheioOuInexistente_deveSerRecusadoAntesDeGravar() {
        assertThatThrownBy(() -> service.criar(conta, UUID.randomUUID(), pedido(alheio)))
                .isInstanceOf(ClienteNaoEncontradoException.class);
        assertThatThrownBy(() -> service.criar(conta, UUID.randomUUID(), pedido(UUID.randomUUID())))
                .isInstanceOf(ClienteNaoEncontradoException.class);
    }

    @Test
    void chamadasConcorrentes_deveCriarUmaCobrancaParaChaveCompartilhada() throws Exception {
        var chave = UUID.randomUUID();
        var prontos = new CountDownLatch(2);
        var iniciar = new CountDownLatch(1);
        Callable<Cobranca> criar = () -> {
            prontos.countDown();
            if (!iniciar.await(10, TimeUnit.SECONDS)) { throw new IllegalStateException("barreira não liberada"); }
            return service.criar(conta, chave, pedido(cliente));
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(criar);
            var b = executor.submit(criar);
            try { assertThat(prontos.await(10, TimeUnit.SECONDS)).isTrue(); }
            finally { iniciar.countDown(); }
            assertThat(a.get(15, TimeUnit.SECONDS)).isEqualTo(b.get(15, TimeUnit.SECONDS));
            assertThatCode(() -> assertThat(contagem()).isEqualTo(1)).doesNotThrowAnyException();
        }
    }

    @Test
    void rollback_deveDesfazerCobrancaEChaveNoMesmoCommit() {
        new TransactionTemplate(transacoes).executeWithoutResult(status -> {
            service.criar(conta, UUID.randomUUID(), pedido(cliente));
            status.setRollbackOnly();
        });
        assertThatCode(() -> assertThat(contagem()).isZero()).doesNotThrowAnyException();
    }

    @Test
    void banco_deveRecusarClienteDeOutraContaEChaveDuplicada() {
        estadoSql("23503", () -> inserirDireto(conta, UUID.randomUUID(), alheio, "12.00", "BRL", "Serviço", "2026-12-10"));
        var chave = UUID.randomUUID();
        assertThatCode(() -> inserirDireto(conta, chave, cliente, "12.00", "BRL", "Serviço", "2026-12-10"))
                .doesNotThrowAnyException();
        estadoSql("23505", () -> inserirDireto(conta, chave, cliente, "12.00", "BRL", "Serviço", "2026-12-10"));
    }

    @Test
    void banco_deveRecusarPrecisaoInvalidaSemCoercaoEOutrosCamposInvalidos() {
        for (var valor : new String[] {"0", "-1", "1.001", "100000000000000000", "NaN", "Infinity", "-Infinity"}) {
            estadoSql("23514", () -> inserirDireto(conta, UUID.randomUUID(), cliente, valor, "BRL", "Serviço", "2026-12-10"));
        }
        estadoSql("23514", () -> inserirDireto(conta, UUID.randomUUID(), cliente, "12", "USD", "Serviço", "2026-12-10"));
        estadoSql("23514", () -> inserirDireto(conta, UUID.randomUUID(), cliente, "12", "BRL", " ", "2026-12-10"));
        estadoSql("23514", () -> inserirDireto(conta, UUID.randomUUID(), cliente, "12", "BRL", "Serviço", "infinity"));
        assertThatCode(() -> inserirDireto(conta, UUID.randomUUID(), cliente, "10.000", "BRL", "Serviço", "2026-12-10"))
                .doesNotThrowAnyException();
    }

    private PedidoCobranca pedido(UUID id) {
        return new PedidoCobranca(id, "Serviço mensal", new BigDecimal("250.00"), "BRL", LocalDate.of(2026, 12, 10));
    }

    private int contagem() {
        return jdbc.queryForObject("SELECT count(*) FROM cobrancas WHERE conta_id = ?", Integer.class, conta);
    }

    private UUID conta() {
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO contas (id, usuario, senha_hash) VALUES (?, ?, ?)", id, "cobranca-" + id, "0".repeat(96));
        return id;
    }

    private UUID cliente(UUID proprietaria) {
        var id = UUID.randomUUID();
        jdbc.update("INSERT INTO clientes (id, conta_id, nome) VALUES (?, ?, ?)", id, proprietaria, "Cliente Exemplo");
        return id;
    }

    private void inserirDireto(UUID proprietaria, UUID chave, UUID clienteId,
            String valor, String moeda, String descricao, String vencimento) {
        jdbc.update("""
                INSERT INTO cobrancas (id, conta_id, chave, cliente_id, descricao, valor, moeda, vencimento, criada_em)
                VALUES (?, ?, ?, ?, ?, CAST(? AS numeric), ?, CAST(? AS date), TIMESTAMPTZ '2026-11-27T18:00:00Z')
                """, UUID.randomUUID(), proprietaria, chave, clienteId, descricao, valor, moeda, vencimento);
    }

    private void estadoSql(String esperado, Runnable escrita) {
        try { escrita.run(); }
        catch (DataAccessException erro) {
            Throwable raiz = erro;
            while (raiz.getCause() != null) { raiz = raiz.getCause(); }
            assertThat(raiz instanceof SQLException).isTrue();
            assertThat(((SQLException) raiz).getSQLState()).isEqualTo(esperado);
            return;
        }
        throw new AssertionError("escrita inválida foi aceita");
    }
}
