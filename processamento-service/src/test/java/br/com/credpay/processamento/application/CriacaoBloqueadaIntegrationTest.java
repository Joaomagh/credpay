package br.com.credpay.processamento.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import com.zaxxer.hikari.HikariDataSource;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "credpay.processamento.limites.BRL=100.00")
@Import(CriacaoBloqueadaIntegrationTest.ColaboradoresControlados.class)
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CriacaoBloqueadaIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgresDaFixture()
            .withDatabaseName("credpay_criacao_bloqueada")
            .withUsername("test").withPassword("test");

    private static HikariDataSource poolDaFixture;

    private static final class PostgresDaFixture extends PostgreSQLContainer {
        private PostgresDaFixture() {
            super("postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0");
        }

        @Override
        public void stop() {
            try {
                assertThat(poolDaFixture).isNotNull();
                assertThat(isRunning()).isTrue();
                assertThat(poolDaFixture.isClosed()).isTrue();
                System.out.println("CredPay fixture lifecycle CriacaoBloqueada: poolClosed=true postgresRunning=true");
            } finally {
                super.stop();
            }
        }
    }

    @DynamicPropertySource
    static void configurarBanco(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private RegistrarProcessamentoService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private HikariDataSource dataSource;
    @Autowired private ProcessamentoRepository processamentos;
    @Autowired private FalhaControlada falha;
    @Autowired private RelogioContado relogio;
    @Autowired private GeradorContado gerador;

    @BeforeEach
    void limparBancoDescartavel() {
        poolDaFixture = dataSource;
        falha.armada.set(false);
        relogio.chamadas.set(0);
        gerador.chamadas.set(0);
        jdbc.execute("TRUNCATE criacao_bloqueada_recebimentos, outbox_encerramentos, encerramentos, outbox_eventos, processamentos");
    }

    @Test
    void executar_deveRegistrarCriacaoBloqueada_semDecisaoOuOutboxFinanceira() {
        var transacao = UUID.randomUUID();
        var entrada = new TransacaoCriadaRecebida(UUID.randomUUID(), transacao,
                Instant.parse("2026-10-14T12:00:00.123456789Z"), transacao,
                new BigDecimal("75.00"), Currency.getInstance("BRL"));
        var pedido = semearConfirmacaoControlada(entrada);

        var recebido = service.executar(entrada);

        assertThat(recebido).isInstanceOf(CriacaoBloqueadaRegistrada.class);
        assertThat(recebido).isEqualTo(new CriacaoBloqueadaRegistrada(entrada, pedido));
        assertThat(contar("processamentos")).isZero();
        assertThat(contar("outbox_eventos")).isZero();
        assertThat(contar("criacao_bloqueada_recebimentos")).isEqualTo(1);
        assertThat(contar("encerramentos")).isEqualTo(1);
        assertThat(contar("outbox_encerramentos")).isEqualTo(1);
        var snapshot = jdbc.queryForMap("SELECT * FROM criacao_bloqueada_recebimentos WHERE event_id = ?",
                entrada.eventId());
        assertThat(snapshot.get("transaction_id")).isEqualTo(entrada.transactionId());
        assertThat(snapshot.get("event_occurred_epoch_second")).isEqualTo(entrada.occurredAt().getEpochSecond());
        assertThat(snapshot.get("event_occurred_nano")).isEqualTo(entrada.occurredAt().getNano());
        assertThat(snapshot.get("correlation_id")).isEqualTo(entrada.correlationId());
        assertThat(snapshot.get("valor")).isEqualTo(entrada.valor());
        assertThat(snapshot.get("moeda")).isEqualTo("BRL");
        assertThat(snapshot.get("request_event_id")).isEqualTo(pedido);
        assertThat(snapshot.get("closure_result")).isEqualTo("CONFIRMADO");
        assertThat(relogio.chamadas.get()).isZero();
        assertThat(gerador.chamadas.get()).isZero();
    }

    @Test
    void executar_deveReutilizarRecibo_quandoReentregaForNumericamenteEquivalente() {
        var entrada = entrada("75.0");
        semearConfirmacaoControlada(entrada);
        var primeiro = service.executar(entrada);
        var snapshot = jdbc.queryForMap("SELECT * FROM criacao_bloqueada_recebimentos");
        var equivalente = new TransacaoCriadaRecebida(entrada.eventId(), entrada.transactionId(),
                entrada.occurredAt(), entrada.correlationId(), new BigDecimal("75.00"), entrada.moeda());

        var replay = service.executar(equivalente);

        assertThat(replay).isEqualTo(primeiro);
        assertThat(contar("criacao_bloqueada_recebimentos")).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT * FROM criacao_bloqueada_recebimentos")).isEqualTo(snapshot);
        assertThat(contar("processamentos")).isZero();
        assertThat(contar("outbox_eventos")).isZero();
        assertThat(relogio.chamadas.get()).isZero();
        assertThat(gerador.chamadas.get()).isZero();
    }

    @ParameterizedTest
    @EnumSource(Divergencia.class)
    void executar_devePreservarReciboEConflitar_quandoCriacaoDivergir(Divergencia divergencia) {
        var entrada = entrada("75.00");
        semearConfirmacaoControlada(entrada);
        service.executar(entrada);
        var snapshot = jdbc.queryForMap("SELECT * FROM criacao_bloqueada_recebimentos");
        var outraTransacao = UUID.randomUUID();
        var outra = switch (divergencia) {
            case VALOR -> new TransacaoCriadaRecebida(entrada.eventId(), entrada.transactionId(),
                    entrada.occurredAt(), entrada.correlationId(), new BigDecimal("76.00"), entrada.moeda());
            case MOEDA -> new TransacaoCriadaRecebida(entrada.eventId(), entrada.transactionId(),
                    entrada.occurredAt(), entrada.correlationId(), entrada.valor(), Currency.getInstance("USD"));
            case INSTANTE -> new TransacaoCriadaRecebida(entrada.eventId(), entrada.transactionId(),
                    entrada.occurredAt().plusNanos(1), entrada.correlationId(), entrada.valor(), entrada.moeda());
            case TRANSACAO -> new TransacaoCriadaRecebida(entrada.eventId(), outraTransacao,
                    entrada.occurredAt(), outraTransacao, entrada.valor(), entrada.moeda());
            case EVENTO -> new TransacaoCriadaRecebida(UUID.randomUUID(), entrada.transactionId(),
                    entrada.occurredAt(), entrada.correlationId(), entrada.valor(), entrada.moeda());
        };

        assertThatThrownBy(() -> service.executar(outra))
                .isInstanceOf(ConflitoProcessamentoException.class)
                .hasMessage("criacao bloqueada divergente");

        assertThat(jdbc.queryForMap("SELECT * FROM criacao_bloqueada_recebimentos")).isEqualTo(snapshot);
        assertThat(contar("processamentos")).isZero();
        assertThat(contar("outbox_eventos")).isZero();
    }

    private enum Divergencia { VALOR, MOEDA, INSTANTE, TRANSACAO, EVENTO }

    @Test
    void executar_deveReverterReciboEPermitirRetry_quandoFalharDepoisDaEscrita() {
        var entrada = entrada("75.00");
        var pedido = semearConfirmacaoControlada(entrada);
        falha.armada.set(true);

        assertThatThrownBy(() -> service.executar(entrada))
                .isInstanceOf(IllegalStateException.class).hasMessage("falha apos recibo bloqueado");

        assertThat(contar("criacao_bloqueada_recebimentos")).isZero();
        assertThat(contar("processamentos")).isZero();
        assertThat(contar("outbox_eventos")).isZero();
        assertThat(contar("encerramentos")).isEqualTo(1);
        assertThat(contar("outbox_encerramentos")).isEqualTo(1);
        falha.armada.set(false);
        assertThat(service.executar(entrada)).isEqualTo(new CriacaoBloqueadaRegistrada(entrada, pedido));
        assertThat(contar("criacao_bloqueada_recebimentos")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void executar_deveAguardarLockEObservarCommitOuRollback_doSeedControlado(boolean confirmar)
            throws Exception {
        var entrada = entrada("75.00");
        var pedido = UUID.randomUUID();
        var entrou = new CountDownLatch(1);
        var liberar = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        Future<UUID> primeira = null;
        Future<RecebimentoProcessamento> segunda = null;
        try {
            primeira = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                processamentos.bloquearIdentidades(pedido, entrada.transactionId());
                semearConfirmacaoControlada(entrada, pedido);
                entrou.countDown();
                try {
                    if (!liberar.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("fixture nao liberou encerramento controlado");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("fixture interrompida", exception);
                }
                if (!confirmar) {
                    status.setRollbackOnly();
                }
                return pedido;
            }));
            assertThat(entrou.await(10, TimeUnit.SECONDS)).isTrue();
            segunda = executor.submit(() -> service.executar(entrada));
            try {
                aguardarLockEmEspera();
            } finally {
                liberar.countDown();
            }
            primeira.get(10, TimeUnit.SECONDS);
            var recebido = segunda.get(10, TimeUnit.SECONDS);
            if (confirmar) {
                assertThat(recebido).isEqualTo(new CriacaoBloqueadaRegistrada(entrada, pedido));
                assertThat(contar("criacao_bloqueada_recebimentos")).isEqualTo(1);
                assertThat(contar("processamentos")).isZero();
                assertThat(contar("outbox_eventos")).isZero();
                assertThat(contar("encerramentos")).isEqualTo(1);
                assertThat(contar("outbox_encerramentos")).isEqualTo(1);
            } else {
                assertThat(recebido).isInstanceOf(ProcessamentoRegistrado.class);
                assertThat(contar("criacao_bloqueada_recebimentos")).isZero();
                assertThat(contar("processamentos")).isEqualTo(1);
                assertThat(contar("outbox_eventos")).isEqualTo(1);
                assertThat(contar("encerramentos")).isZero();
                assertThat(contar("outbox_encerramentos")).isZero();
            }
        } finally {
            liberar.countDown();
            if (primeira != null && !primeira.isDone()) {
                primeira.cancel(true);
            }
            if (segunda != null && !segunda.isDone()) {
                segunda.cancel(true);
            }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS))
                    .withFailMessage("workers da fixture nao terminaram apos cleanup").isTrue();
        }
    }

    private void aguardarLockEmEspera() {
        var fim = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < fim) {
            Integer aguardando = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted
                      AND database = (SELECT oid FROM pg_database WHERE datname = current_database())
                    """, Integer.class);
            if (aguardando != null && aguardando > 0) {
                return;
            }
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
        throw new AssertionError("criacao normal nao aguardou advisory lock do seed");
    }

    @ParameterizedTest
    @EnumSource(CampoInvalido.class)
    void schema_devePreservarRecibo_quandoDinheiroOuMoedaForInvalido(CampoInvalido campo) {
        var entrada = entrada("75.00");
        semearConfirmacaoControlada(entrada);
        service.executar(entrada);
        var snapshot = jdbc.queryForMap("SELECT * FROM criacao_bloqueada_recebimentos");

        var falha = catchThrowable(() -> jdbc.update("UPDATE criacao_bloqueada_recebimentos SET "
                + campo.coluna + " = " + campo.literalSql));

        assertThat(falha).isInstanceOf(DataIntegrityViolationException.class);
        var causa = ((DataIntegrityViolationException) falha).getMostSpecificCause();
        assertThat(causa).isInstanceOf(PSQLException.class);
        var erroPostgres = (PSQLException) causa;
        assertThat(erroPostgres.getSQLState()).isEqualTo("23514");
        assertThat(erroPostgres.getServerErrorMessage().getConstraint()).isEqualTo(campo.constraint);
        assertThat(jdbc.queryForMap("SELECT * FROM criacao_bloqueada_recebimentos")).isEqualTo(snapshot);
    }

    private enum CampoInvalido {
        NAN("valor", "'NaN'::numeric", "ck_blocked_creation_valor_positivo_finito"),
        INFINITO("valor", "'Infinity'::numeric", "ck_blocked_creation_valor_positivo_finito"),
        MOEDA_MINUSCULA("moeda", "'brl'", "ck_blocked_creation_moeda_formato"),
        MOEDA_CURTA("moeda", "'BR'", "ck_blocked_creation_moeda_formato"),
        MOEDA_VAZIA("moeda", "''", "ck_blocked_creation_moeda_formato");

        private final String coluna;
        private final String literalSql;
        private final String constraint;

        CampoInvalido(String coluna, String literalSql, String constraint) {
            this.coluna = coluna;
            this.literalSql = literalSql;
            this.constraint = constraint;
        }
    }

    private TransacaoCriadaRecebida entrada(String valor) {
        var transacao = UUID.randomUUID();
        return new TransacaoCriadaRecebida(UUID.randomUUID(), transacao,
                Instant.parse("2026-10-14T12:00:00.123456789Z"), transacao,
                new BigDecimal(valor), Currency.getInstance("BRL"));
    }

    private UUID semearConfirmacaoControlada(TransacaoCriadaRecebida entrada) {
        return semearConfirmacaoControlada(entrada, UUID.randomUUID());
    }

    private UUID semearConfirmacaoControlada(TransacaoCriadaRecebida entrada, UUID pedido) {
        var resposta = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("""
                    INSERT INTO encerramentos (request_event_id, transaction_id, creation_event_id,
                        event_type, event_version, request_occurred_epoch_second, request_occurred_nano,
                        correlation_id, motivo, evidence_id, resultado, response_event_id, responded_at)
                    VALUES (?, ?, ?, 'EncerramentoTransacaoSolicitado', 1, ?, ?, ?,
                        'FALHA_PERMANENTE_CONTROLADA', ?, 'CONFIRMADO', ?, CURRENT_TIMESTAMP)
                    """, pedido, entrada.transactionId(), entrada.eventId(),
                    entrada.occurredAt().getEpochSecond(), entrada.occurredAt().getNano(),
                    entrada.transactionId(), UUID.randomUUID(), resposta);
            jdbc.update("""
                    INSERT INTO outbox_encerramentos (event_id, aggregate_id, event_type,
                        event_version, payload, occurred_at)
                    VALUES (?, ?, 'EncerramentoTransacaoRespondido', 1, '{}'::jsonb, CURRENT_TIMESTAMP)
                    """, resposta, entrada.transactionId());
        });
        return pedido;
    }

    private int contar(String tabelaDaFixture) {
        return jdbc.queryForObject("SELECT count(*) FROM " + tabelaDaFixture, Integer.class);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ColaboradoresControlados {
        @Bean @Primary
        FalhaControlada reciboControlado(
                @Qualifier("criacaoBloqueadaJdbcRepository") CriacaoBloqueadaRepository delegate) {
            return new FalhaControlada(delegate);
        }

        @Bean @Primary RelogioContado relogioControlado() { return new RelogioContado(); }
        @Bean @Primary GeradorContado geradorControlado() { return new GeradorContado(); }
    }

    static final class FalhaControlada implements CriacaoBloqueadaRepository {
        private final CriacaoBloqueadaRepository delegate;
        private final AtomicBoolean armada = new AtomicBoolean();

        FalhaControlada(CriacaoBloqueadaRepository delegate) { this.delegate = delegate; }

        @Override public Optional<BloqueioConfirmado> buscarPedidoConfirmadoPorTransactionId(UUID id) {
            return delegate.buscarPedidoConfirmadoPorTransactionId(id);
        }

        @Override public Optional<CriacaoBloqueadaRegistrada> buscarRecebimentoPorEventId(UUID id) {
            return delegate.buscarRecebimentoPorEventId(id);
        }

        @Override public void inserir(CriacaoBloqueadaRegistrada recibo) {
            delegate.inserir(recibo);
            if (armada.get()) {
                throw new IllegalStateException("falha apos recibo bloqueado");
            }
        }
    }

    static final class RelogioContado extends Clock {
        private final AtomicInteger chamadas = new AtomicInteger();
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        @Override public Instant instant() {
            chamadas.incrementAndGet();
            return Instant.parse("2026-10-14T12:00:01.987654Z");
        }
    }

    static final class GeradorContado implements GeradorEventIdSaida {
        private final AtomicInteger chamadas = new AtomicInteger();
        @Override public UUID gerar() {
            chamadas.incrementAndGet();
            return UUID.randomUUID();
        }
    }
}
