package br.com.credpay.processamento.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import com.zaxxer.hikari.HikariDataSource;
import org.postgresql.util.PSQLException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "credpay.processamento.limites.BRL=100.00")
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CriacaoBloqueadaIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgresDaFixture()
            .withDatabaseName("credpay_criacao_bloqueada")
            .withUsername("test").withPassword("test");

    private static HikariDataSource poolDaFixture;

    private static final class PostgresDaFixture extends PostgreSQLContainer<PostgresDaFixture> {
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

    @BeforeEach
    void limparBancoDescartavel() {
        poolDaFixture = dataSource;
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
        var pedido = UUID.randomUUID();
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
}
