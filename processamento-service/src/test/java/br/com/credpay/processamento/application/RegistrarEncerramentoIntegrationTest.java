package br.com.credpay.processamento.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import java.math.BigDecimal;
import java.util.Currency;

import com.zaxxer.hikari.HikariDataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "credpay.processamento.limites.BRL=100.00")
@Import({RegistrarEncerramentoService.class, RegistrarEncerramentoIntegrationTest.RelogioControlado.class})
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RegistrarEncerramentoIntegrationTest {

    private static HikariDataSource poolDaFixture;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgresDaFixture()
            .withDatabaseName("credpay_registrar_encerramento")
            .withUsername("test").withPassword("test");

    private static final class PostgresDaFixture extends PostgreSQLContainer<PostgresDaFixture> {
        private PostgresDaFixture() {
            super("postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0");
        }

        @Override public void stop() {
            try {
                assertThat(poolDaFixture).isNotNull();
                assertThat(isRunning()).isTrue();
                assertThat(poolDaFixture.isClosed()).isTrue();
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

    @Autowired private RegistrarEncerramentoService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private HikariDataSource dataSource;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RegistrarProcessamentoService financeiro;

    @BeforeEach void limparBancoDescartavel() {
        poolDaFixture = dataSource;
        jdbc.execute("TRUNCATE criacao_bloqueada_recebimentos, outbox_encerramentos, encerramentos, outbox_eventos, processamentos");
    }

    @Test
    void executar_deveCommitarRegistroERespostaPropria_quandoNaoExistirDecisao() throws Exception {
        var transacao = UUID.randomUUID();
        var pedido = new PedidoEncerramentoRecebido(UUID.randomUUID(), transacao, UUID.randomUUID(),
                Instant.parse("2026-10-14T12:00:00.123456789Z"), transacao,
                PedidoEncerramentoRecebido.Motivo.FALHA_PERMANENTE_CONTROLADA, UUID.randomUUID());

        var resultado = service.executar(pedido);

        var quantidade = jdbc.queryForObject("SELECT count(*) FROM encerramentos WHERE request_event_id = ?",
                Integer.class, pedido.eventId());
        assertThat(quantidade).withFailMessage("pedido apos commit: esperado1, observado%s", quantidade).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM encerramentos e JOIN outbox_encerramentos o
                  ON o.event_id = e.response_event_id AND o.aggregate_id = e.transaction_id
                WHERE e.request_event_id = ? AND e.resultado = 'CONFIRMADO'
                  AND o.published_at IS NULL
                """, Integer.class, pedido.eventId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processamentos", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_eventos", Integer.class)).isZero();
        assertThat(resultado.pedido()).isEqualTo(pedido);
        assertThat(resultado.resultado()).isEqualTo(EncerramentoRegistrado.Resultado.CONFIRMADO);
        assertThat(resultado.respondedAt()).isEqualTo(Instant.parse("2026-10-14T12:00:01.987654Z"));
        assertThat(resultado.outputEventId()).isEmpty();
        var snapshot = jdbc.queryForMap("SELECT * FROM encerramentos WHERE request_event_id = ?", pedido.eventId());
        assertThat(snapshot.get("transaction_id")).isEqualTo(pedido.transactionId());
        assertThat(snapshot.get("creation_event_id")).isEqualTo(pedido.creationEventId());
        assertThat(snapshot.get("correlation_id")).isEqualTo(pedido.correlationId());
        assertThat(snapshot.get("request_occurred_epoch_second")).isEqualTo(pedido.occurredAt().getEpochSecond());
        assertThat(snapshot.get("request_occurred_nano")).isEqualTo(pedido.occurredAt().getNano());
        assertThat(snapshot.get("evidence_id")).isEqualTo(pedido.evidenceRef());
        assertThat(snapshot.get("response_event_id")).isEqualTo(resultado.responseEventId());
        Instant respondidoNoBanco = jdbc.queryForObject(
                "SELECT responded_at FROM encerramentos WHERE request_event_id = ?",
                (rs, linha) -> rs.getTimestamp(1).toInstant(), pedido.eventId());
        assertThat(respondidoNoBanco).isEqualTo(resultado.respondedAt());
        var json = objectMapper.readTree(jdbc.queryForObject(
                "SELECT payload::text FROM outbox_encerramentos WHERE event_id = ?", String.class,
                resultado.responseEventId()));
        assertThat(json.path("eventId").asText()).isEqualTo(resultado.responseEventId().toString());
        assertThat(json.path("eventType").asText()).isEqualTo("EncerramentoTransacaoRespondido");
        assertThat(json.path("eventVersion").asInt()).isEqualTo(1);
        assertThat(json.path("occurredAt").asText()).isEqualTo(resultado.respondedAt().toString());
        assertThat(json.path("correlationId").asText()).isEqualTo(pedido.transactionId().toString());
        assertThat(json.path("causationId").asText()).isEqualTo(pedido.eventId().toString());
        assertThat(json.path("data").path("transactionId").asText()).isEqualTo(pedido.transactionId().toString());
        assertThat(json.path("data").path("creationEventId").asText()).isEqualTo(pedido.creationEventId().toString());
        assertThat(json.path("data").path("resultado").asText()).isEqualTo("CONFIRMADO");
        assertThat(json.path("data").has("outputEventId")).isFalse();
        assertThat(json.path("data").size()).isEqualTo(3);
    }

    @ParameterizedTest
    @CsvSource({"75.00,APROVADA", "101.00,REJEITADA"})
    void executar_deveResponderNegativaEPreservarVencedor_quandoDecisaoExistir(String valor, String status)
            throws Exception {
        var entrada = entrada(valor);
        var decisao = (ProcessamentoRegistrado) financeiro.executar(entrada);
        assertThat(decisao.status().name()).isEqualTo(status);
        var snapshot = jdbc.queryForMap("SELECT * FROM processamentos");
        var saida = jdbc.queryForMap("SELECT * FROM outbox_eventos");
        var pedido = pedido(entrada.transactionId(), entrada.eventId());

        var resultado = service.executar(pedido);

        assertThat(resultado.resultado()).isEqualTo(EncerramentoRegistrado.Resultado.DECISAO_EXISTENTE);
        assertThat(resultado.outputEventId()).contains(decisao.outputEventId());
        assertThat(jdbc.queryForMap("SELECT * FROM processamentos")).isEqualTo(snapshot);
        assertThat(jdbc.queryForMap("SELECT * FROM outbox_eventos")).isEqualTo(saida);
        assertThat(financeiro.executar(entrada)).isEqualTo(decisao);
        var registro = jdbc.queryForMap("SELECT * FROM encerramentos");
        assertThat(registro.get("resultado")).isEqualTo("DECISAO_EXISTENTE");
        assertThat(registro.get("output_event_id")).isEqualTo(decisao.outputEventId());
        var json = objectMapper.readTree(jdbc.queryForObject("SELECT payload::text FROM outbox_encerramentos", String.class));
        assertThat(json.path("causationId").asText()).isEqualTo(pedido.eventId().toString());
        assertThat(json.path("data").path("resultado").asText()).isEqualTo("DECISAO_EXISTENTE");
        assertThat(json.path("data").path("outputEventId").asText()).isEqualTo(decisao.outputEventId().toString());
        assertThat(json.path("data").size()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM criacao_bloqueada_recebimentos", Integer.class)).isZero();
    }

    @ParameterizedTest
    @EnumSource(IdentidadeDivergente.class)
    void executar_deveConflitarSemEscrita_quandoIdentidadeFinanceiraDivergir(IdentidadeDivergente campo) {
        var entrada = entrada("75.00");
        financeiro.executar(entrada);
        var snapshot = jdbc.queryForMap("SELECT * FROM processamentos");
        var saida = jdbc.queryForMap("SELECT * FROM outbox_eventos");
        var pedido = campo == IdentidadeDivergente.CRIACAO
                ? pedido(entrada.transactionId(), UUID.randomUUID())
                : pedido(UUID.randomUUID(), entrada.eventId());

        assertThatThrownBy(() -> service.executar(pedido))
                .isInstanceOf(ConflitoProcessamentoException.class)
                .hasMessage("identidade do encerramento divergente");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM encerramentos", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_encerramentos", Integer.class)).isZero();
        assertThat(jdbc.queryForMap("SELECT * FROM processamentos")).isEqualTo(snapshot);
        assertThat(jdbc.queryForMap("SELECT * FROM outbox_eventos")).isEqualTo(saida);
    }

    private enum IdentidadeDivergente { CRIACAO, TRANSACAO }

    private TransacaoCriadaRecebida entrada(String valor) {
        var transacao = UUID.randomUUID();
        return new TransacaoCriadaRecebida(UUID.randomUUID(), transacao,
                Instant.parse("2026-10-14T12:00:00.123456789Z"), transacao,
                new BigDecimal(valor), Currency.getInstance("BRL"));
    }

    private PedidoEncerramentoRecebido pedido(UUID transacao, UUID criacao) {
        return new PedidoEncerramentoRecebido(UUID.randomUUID(), transacao, criacao,
                Instant.parse("2026-10-14T12:00:00.123456789Z"), transacao,
                PedidoEncerramentoRecebido.Motivo.FALHA_PERMANENTE_CONTROLADA, UUID.randomUUID());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RelogioControlado {
        @Bean @Primary Clock relogioEncerramento() {
            return Clock.fixed(Instant.parse("2026-10-14T12:00:01.987654321Z"), ZoneOffset.UTC);
        }
    }
}
