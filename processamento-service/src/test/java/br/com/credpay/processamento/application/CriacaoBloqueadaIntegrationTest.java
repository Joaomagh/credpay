package br.com.credpay.processamento.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
