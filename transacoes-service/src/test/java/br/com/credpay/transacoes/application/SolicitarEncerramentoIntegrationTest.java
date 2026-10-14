package br.com.credpay.transacoes.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import br.com.credpay.transacoes.domain.StatusTransacao;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Import({SolicitarEncerramentoService.class, SolicitarEncerramentoIntegrationTest.Configuracao.class})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class SolicitarEncerramentoIntegrationTest {

    private static HikariDataSource poolDaFixture;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgresDaFixture()
            .withDatabaseName("credpay_solicitar_encerramento").withUsername("test").withPassword("test");

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

    @Autowired private CriarTransacao criar;
    @Autowired private SolicitarEncerramentoService solicitar;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private HikariDataSource dataSource;
    @Autowired private ObjectMapper mapper;
    @Autowired private AplicarResultadoService aplicar;
    @Autowired private RelogioControlado clock;

    @BeforeEach void preparar() {
        poolDaFixture = dataSource;
        clock.instante = Instant.parse("2026-10-14T18:00:00.123456789Z");
        clock.chamadas.set(0);
        jdbc.execute("""
                TRUNCATE outbox_pedidos_encerramento, pedidos_encerramento,
                  historico_transacoes, outbox_eventos, idempotencias_transacao, transacoes
                """);
    }

    @Test
    void executar_deveGravarPedidoEIntencao_semMudarTransacaoOuRespostaOriginal() throws Exception {
        var chave = UUID.randomUUID();
        var original = criar.executar(chave, new BigDecimal("75.00"), Currency.getInstance("BRL"));
        var transacao = jdbc.queryForMap("SELECT * FROM transacoes");
        var criacao = jdbc.queryForMap("SELECT * FROM outbox_eventos");
        var historico = jdbc.queryForList("SELECT * FROM historico_transacoes");
        var pedido = UUID.randomUUID();

        var evidencia = UUID.randomUUID();
        var registrado = solicitar.executar(pedido, original.id(), evidencia);

        var quantidade = jdbc.queryForObject("SELECT count(*) FROM pedidos_encerramento WHERE event_id = ?",
                Integer.class, pedido);
        assertThat(quantidade).withFailMessage("pedido esperado1, observado%s", quantidade).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pedidos_encerramento p JOIN outbox_pedidos_encerramento o
                  ON o.event_id = p.event_id AND o.aggregate_id = p.transaction_id
                WHERE p.event_id = ? AND o.published_at IS NULL
                """, Integer.class, pedido)).isEqualTo(1);
        var snapshot = jdbc.queryForMap("SELECT * FROM pedidos_encerramento WHERE event_id = ?", pedido);
        assertThat(snapshot).containsEntry("transaction_id", original.id())
                .containsEntry("creation_event_id", criacao.get("event_id"))
                .containsEntry("correlation_id", original.id())
                .containsEntry("motivo", "FALHA_PERMANENTE_CONTROLADA")
                .containsEntry("evidence_ref", evidencia);
        assertThat(Instant.ofEpochSecond(((Number) snapshot.get("occurred_epoch_second")).longValue(),
                ((Number) snapshot.get("occurred_nano")).longValue())).isEqualTo(registrado.occurredAt());
        var json = mapper.readTree(jdbc.queryForObject(
                "SELECT payload::text FROM outbox_pedidos_encerramento WHERE event_id = ?", String.class, pedido));
        assertThat(json.path("eventId").asText()).isEqualTo(pedido.toString());
        assertThat(json.path("eventType").asText()).isEqualTo("EncerramentoTransacaoSolicitado");
        assertThat(json.path("eventVersion").asInt()).isEqualTo(1);
        assertThat(json.path("occurredAt").asText()).isEqualTo(registrado.occurredAt().toString());
        assertThat(json.path("correlationId").asText()).isEqualTo(original.id().toString());
        assertThat(json.path("causationId").asText()).isEqualTo(criacao.get("event_id").toString());
        assertThat(json.path("data").size()).isEqualTo(4);
        assertThat(json.path("data").path("transactionId").asText()).isEqualTo(original.id().toString());
        assertThat(json.path("data").path("creationEventId").asText()).isEqualTo(criacao.get("event_id").toString());
        assertThat(json.path("data").path("motivo").asText()).isEqualTo("FALHA_PERMANENTE_CONTROLADA");
        assertThat(json.path("data").path("evidenceRef").asText()).isEqualTo(evidencia.toString());
        assertThat(jdbc.queryForMap("SELECT * FROM transacoes")).isEqualTo(transacao);
        assertThat(jdbc.queryForMap("SELECT * FROM outbox_eventos")).isEqualTo(criacao);
        assertThat(jdbc.queryForList("SELECT * FROM historico_transacoes")).isEqualTo(historico);
        assertThat(criar.executar(chave, new BigDecimal("75.0"), Currency.getInstance("BRL"))).isEqualTo(original);
    }

    @Test
    void executar_deveRecusarTransacaoInexistente_semEscrita() {
        assertThatThrownBy(() -> solicitar.executar(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(PedidoEncerramentoRecusadoException.class)
                .hasMessage("transação inexistente ou não pendente");
        assertSemPedido();
    }

    @ParameterizedTest
    @EnumSource(value = StatusTransacao.class, names = {"APROVADA", "REJEITADA"})
    void executar_deveRecusarTransacaoFinal_semAlterarVencedor(StatusTransacao estado) {
        var original = criar.executar(UUID.randomUUID(), new BigDecimal("75.00"), Currency.getInstance("BRL"));
        var criacao = jdbc.queryForObject("SELECT event_id FROM outbox_eventos", UUID.class);
        aplicar.executar(new TransacaoProcessadaRecebida(UUID.randomUUID(), original.id(),
                Instant.parse("2026-10-14T18:00:00.123456789Z"), original.id(), criacao, estado));
        var transacao = jdbc.queryForMap("SELECT * FROM transacoes");
        var historico = jdbc.queryForList("SELECT * FROM historico_transacoes");

        assertThatThrownBy(() -> solicitar.executar(UUID.randomUUID(), original.id(), UUID.randomUUID()))
                .isInstanceOf(PedidoEncerramentoRecusadoException.class)
                .hasMessage("transação inexistente ou não pendente");

        assertSemPedido();
        assertThat(jdbc.queryForMap("SELECT * FROM transacoes")).isEqualTo(transacao);
        assertThat(jdbc.queryForList("SELECT * FROM historico_transacoes")).isEqualTo(historico);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ausente", "tipo", "versao", "ambigua"})
    void executar_deveRecusarCriacaoNaoUnivoca_semEscrita(String cenario) {
        var original = criar.executar(UUID.randomUUID(), new BigDecimal("75.00"), Currency.getInstance("BRL"));
        switch (cenario) {
            case "ausente" -> jdbc.update("DELETE FROM outbox_eventos");
            case "tipo" -> jdbc.update("UPDATE outbox_eventos SET event_type = 'OutroEvento'");
            case "versao" -> jdbc.update("UPDATE outbox_eventos SET event_version = 2");
            case "ambigua" -> jdbc.update("""
                    INSERT INTO outbox_eventos (event_id, aggregate_id, event_type, event_version, payload, occurred_at)
                    SELECT ?, aggregate_id, event_type, event_version, payload, occurred_at FROM outbox_eventos
                    """, UUID.randomUUID());
            default -> throw new IllegalArgumentException(cenario);
        }
        var criacao = jdbc.queryForList("SELECT * FROM outbox_eventos ORDER BY event_id");

        assertThatThrownBy(() -> solicitar.executar(UUID.randomUUID(), original.id(), UUID.randomUUID()))
                .isInstanceOf(PedidoEncerramentoRecusadoException.class)
                .hasMessage("criação original ausente ou ambígua");

        assertSemPedido();
        assertThat(jdbc.queryForList("SELECT * FROM outbox_eventos ORDER BY event_id")).isEqualTo(criacao);
        assertThat(jdbc.queryForObject("SELECT status FROM transacoes", String.class)).isEqualTo("PENDENTE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM historico_transacoes", Integer.class)).isZero();
    }

    private void assertSemPedido() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pedidos_encerramento", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_pedidos_encerramento", Integer.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDENTE", "APROVADA", "REJEITADA"})
    void executar_deveRepetirPedidoOriginal_antesDoEstadoOuRelogio(String estado) {
        var original = criar.executar(UUID.randomUUID(), new BigDecimal("75.00"), Currency.getInstance("BRL"));
        var pedidoId = UUID.randomUUID();
        var evidencia = UUID.randomUUID();
        var pedido = solicitar.executar(pedidoId, original.id(), evidencia);
        jdbc.update("UPDATE outbox_pedidos_encerramento SET published_at = CURRENT_TIMESTAMP");
        if (!estado.equals("PENDENTE")) {
            aplicar.executar(new TransacaoProcessadaRecebida(UUID.randomUUID(), original.id(), clock.instante,
                    original.id(), pedido.creationEventId(), StatusTransacao.valueOf(estado)));
        }
        var antes = jdbc.queryForMap("SELECT * FROM pedidos_encerramento");
        var intencao = jdbc.queryForMap("SELECT * FROM outbox_pedidos_encerramento");
        var transacao = jdbc.queryForMap("SELECT * FROM transacoes");
        var historico = jdbc.queryForList("SELECT * FROM historico_transacoes");
        var chamadas = clock.chamadas.get();
        clock.instante = clock.instante.plusSeconds(100);

        assertThat(solicitar.executar(pedidoId, original.id(), evidencia)).isEqualTo(pedido);

        assertThat(clock.chamadas.get()).isEqualTo(chamadas);
        assertThat(jdbc.queryForMap("SELECT * FROM pedidos_encerramento")).isEqualTo(antes);
        assertThat(jdbc.queryForMap("SELECT * FROM outbox_pedidos_encerramento")).isEqualTo(intencao);
        assertThat(jdbc.queryForMap("SELECT * FROM transacoes")).isEqualTo(transacao);
        assertThat(jdbc.queryForList("SELECT * FROM historico_transacoes")).isEqualTo(historico);
    }

    @ParameterizedTest
    @ValueSource(strings = {"evidencia", "transacao", "pedido"})
    void executar_deveRecusarPedidoDivergente_semSubstituirOriginal(String campo) {
        var original = criar.executar(UUID.randomUUID(), new BigDecimal("75.00"), Currency.getInstance("BRL"));
        var outra = criar.executar(UUID.randomUUID(), new BigDecimal("20.00"), Currency.getInstance("BRL"));
        var pedidoId = UUID.randomUUID();
        var evidencia = UUID.randomUUID();
        solicitar.executar(pedidoId, original.id(), evidencia);
        var antes = jdbc.queryForMap("SELECT * FROM pedidos_encerramento");
        var intencao = jdbc.queryForMap("SELECT * FROM outbox_pedidos_encerramento");
        var chamadas = clock.chamadas.get();

        assertThatThrownBy(() -> solicitar.executar(campo.equals("pedido") ? UUID.randomUUID() : pedidoId,
                campo.equals("transacao") ? outra.id() : original.id(),
                campo.equals("evidencia") ? UUID.randomUUID() : evidencia))
                .isInstanceOf(PedidoEncerramentoRecusadoException.class)
                .hasMessage("pedido de encerramento divergente");

        assertThat(clock.chamadas.get()).isEqualTo(chamadas);
        assertThat(jdbc.queryForMap("SELECT * FROM pedidos_encerramento")).isEqualTo(antes);
        assertThat(jdbc.queryForMap("SELECT * FROM outbox_pedidos_encerramento")).isEqualTo(intencao);
    }

    @ParameterizedTest
    @ValueSource(strings = {"origem_inexistente", "origem_outra_transacao", "correlacao", "motivo", "nano_pedido",
            "intencao_orfa", "agregado_intencao", "tipo_intencao", "versao_intencao", "payload_intencao",
            "nano_intencao"})
    void banco_deveRecusarQuebraDeCausalidadeOuEstrutura_semAlterarPar(String cenario) {
        var original = criar.executar(UUID.randomUUID(), new BigDecimal("75.00"), Currency.getInstance("BRL"));
        var outra = criar.executar(UUID.randomUUID(), new BigDecimal("20.00"), Currency.getInstance("BRL"));
        solicitar.executar(UUID.randomUUID(), original.id(), UUID.randomUUID());
        var antes = jdbc.queryForMap("SELECT * FROM pedidos_encerramento");
        var intencao = jdbc.queryForMap("SELECT * FROM outbox_pedidos_encerramento");
        var outraCriacao = jdbc.queryForObject("SELECT event_id FROM outbox_eventos WHERE aggregate_id = ?",
                UUID.class, outra.id());

        assertThatThrownBy(() -> {
            switch (cenario) {
                case "origem_inexistente" -> jdbc.update(
                        "UPDATE pedidos_encerramento SET creation_event_id = ?", UUID.randomUUID());
                case "origem_outra_transacao" -> jdbc.update(
                        "UPDATE pedidos_encerramento SET creation_event_id = ?", outraCriacao);
                case "correlacao" -> jdbc.update(
                        "UPDATE pedidos_encerramento SET correlation_id = ?", outra.id());
                case "motivo" -> jdbc.update("UPDATE pedidos_encerramento SET motivo = 'OUTRO'");
                case "nano_pedido" -> jdbc.update("UPDATE pedidos_encerramento SET occurred_nano = -1");
                case "intencao_orfa" -> jdbc.update(
                        "UPDATE outbox_pedidos_encerramento SET event_id = ?", UUID.randomUUID());
                case "agregado_intencao" -> jdbc.update(
                        "UPDATE outbox_pedidos_encerramento SET aggregate_id = ?", outra.id());
                case "tipo_intencao" -> jdbc.update("UPDATE outbox_pedidos_encerramento SET event_type = 'OUTRO'");
                case "versao_intencao" -> jdbc.update("UPDATE outbox_pedidos_encerramento SET event_version = 2");
                case "payload_intencao" -> jdbc.update("UPDATE outbox_pedidos_encerramento SET payload = '[]'::jsonb");
                case "nano_intencao" -> jdbc.update("UPDATE outbox_pedidos_encerramento SET occurred_nano = 1000000000");
                default -> throw new IllegalArgumentException(cenario);
            }
        }).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForMap("SELECT * FROM pedidos_encerramento")).isEqualTo(antes);
        assertThat(jdbc.queryForMap("SELECT * FROM outbox_pedidos_encerramento")).isEqualTo(intencao);
    }

    @Test
    void banco_deveImpedirSegundoPedidoDaMesmaTransacao() {
        var original = criar.executar(UUID.randomUUID(), new BigDecimal("75.00"), Currency.getInstance("BRL"));
        solicitar.executar(UUID.randomUUID(), original.id(), UUID.randomUUID());

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO pedidos_encerramento
                  (event_id, transaction_id, creation_event_id, occurred_epoch_second, occurred_nano,
                   correlation_id, motivo, evidence_ref)
                SELECT ?, transaction_id, creation_event_id, occurred_epoch_second, occurred_nano,
                       correlation_id, motivo, evidence_ref FROM pedidos_encerramento
                """, UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM pedidos_encerramento", Integer.class)).isEqualTo(1);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuracao {
        @Bean @Primary RelogioControlado relogioControlado() { return new RelogioControlado(); }
    }

    static class RelogioControlado extends Clock {
        private volatile Instant instante = Instant.parse("2026-10-14T18:00:00.123456789Z");
        private final AtomicInteger chamadas = new AtomicInteger();

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { chamadas.incrementAndGet(); return instante; }
    }
}
