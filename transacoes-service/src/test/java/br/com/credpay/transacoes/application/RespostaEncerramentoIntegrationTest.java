package br.com.credpay.transacoes.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Import(SolicitarEncerramentoService.class)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class RespostaEncerramentoIntegrationTest {

    private static HikariDataSource poolDaFixture;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgresDaFixture()
            .withDatabaseName("credpay_resposta_encerramento").withUsername("test").withPassword("test");

    private static final class PostgresDaFixture extends PostgreSQLContainer {
        private PostgresDaFixture() {
            super("postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0");
        }

        @Override public void stop() {
            try {
                assertThat(poolDaFixture).isNotNull();
                assertThat(isRunning()).isTrue();
                assertThat(poolDaFixture.isClosed()).isTrue();
            } finally { super.stop(); }
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
    @Autowired private RespostaEncerramentoRepository respostas;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private HikariDataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;

    @BeforeEach void preparar() {
        poolDaFixture = dataSource;
        jdbc.execute("""
                TRUNCATE respostas_encerramento_recebidas, outbox_pedidos_encerramento, pedidos_encerramento,
                  historico_transacoes, outbox_eventos, idempotencias_transacao, transacoes
                """);
    }

    @ParameterizedTest
    @EnumSource(RespostaEncerramentoRecebida.Resultado.class)
    void inserir_devePreservarRespostaCompletaAposCommit_semAplicarEstado(
            RespostaEncerramentoRecebida.Resultado resultado) {
        var chave = UUID.randomUUID();
        var original = criar.executar(chave, new BigDecimal("75.00"), Currency.getInstance("BRL"));
        var pedido = solicitar.executar(UUID.randomUUID(), original.id(), UUID.randomUUID());
        var transacao = jdbc.queryForMap("SELECT * FROM transacoes WHERE id = ?", original.id());
        var historico = jdbc.queryForList("SELECT * FROM historico_transacoes WHERE transaction_id = ?", original.id());
        var snapshotPedido = jdbc.queryForMap("SELECT * FROM pedidos_encerramento WHERE event_id = ?", pedido.eventId());
        var output = resultado == RespostaEncerramentoRecebida.Resultado.DECISAO_EXISTENTE
                ? Optional.of(UUID.randomUUID()) : Optional.<UUID>empty();
        var resposta = new RespostaEncerramentoRecebida(UUID.randomUUID(), original.id(), pedido.creationEventId(),
                Instant.parse("2026-10-14T18:00:00.123456789Z"), original.id(), pedido.eventId(), resultado, output);

        respostas.inserir(resposta);

        var snapshot = jdbc.queryForMap("SELECT * FROM respostas_encerramento_recebidas WHERE event_id = ?", resposta.eventId());
        assertThat(snapshot).containsEntry("transaction_id", resposta.transactionId())
                .containsEntry("creation_event_id", resposta.creationEventId())
                .containsEntry("event_type", "EncerramentoTransacaoRespondido")
                .containsEntry("event_version", 1)
                .containsEntry("correlation_id", resposta.correlationId())
                .containsEntry("causation_id", resposta.causationId())
                .containsEntry("resultado", resultado.name())
                .containsEntry("output_event_id", output.orElse(null))
                .containsEntry("occurred_nano", 123456789)
                .containsEntry("occurred_epoch_second", resposta.occurredAt().getEpochSecond());
        assertThat(respostas.buscarPorEvento(resposta.eventId())).contains(resposta);
        assertThat(respostas.buscarPorEvento(UUID.randomUUID())).isEmpty();
        assertThat(jdbc.queryForMap("SELECT * FROM transacoes WHERE id = ?", original.id())).isEqualTo(transacao);
        assertThat(jdbc.queryForList("SELECT * FROM historico_transacoes WHERE transaction_id = ?", original.id()))
                .isEqualTo(historico);
        assertThat(jdbc.queryForMap("SELECT * FROM pedidos_encerramento WHERE event_id = ?", pedido.eventId()))
                .isEqualTo(snapshotPedido);
        assertThat(criar.executar(chave, new BigDecimal("75.0"), Currency.getInstance("BRL"))).isEqualTo(original);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pedido_inexistente", "pedido", "transacao", "criacao", "tipo", "versao",
            "correlacao", "nano_negativo", "nano_alto", "resultado", "saida_extra", "saida_ausente"})
    void banco_deveRecusarVinculoOuEstruturaInvalidos_semAlterarRecibo(String campo) {
        var original = prepararResposta(campo.equals("saida_ausente")
                ? RespostaEncerramentoRecebida.Resultado.DECISAO_EXISTENTE
                : RespostaEncerramentoRecebida.Resultado.CONFIRMADO);
        var outra = prepararResposta(RespostaEncerramentoRecebida.Resultado.CONFIRMADO);
        respostas.inserir(original);
        var antes = jdbc.queryForMap("SELECT * FROM respostas_encerramento_recebidas");

        var falha = catchThrowable(() -> {
            switch (campo) {
                case "pedido_inexistente" -> jdbc.update(
                        "UPDATE respostas_encerramento_recebidas SET causation_id = ?", UUID.randomUUID());
                case "pedido" -> jdbc.update(
                        "UPDATE respostas_encerramento_recebidas SET causation_id = ?", outra.causationId());
                case "transacao" -> jdbc.update("""
                        UPDATE respostas_encerramento_recebidas SET transaction_id = ?, correlation_id = ?
                        """, outra.transactionId(), outra.transactionId());
                case "criacao" -> jdbc.update(
                        "UPDATE respostas_encerramento_recebidas SET creation_event_id = ?", outra.creationEventId());
                case "tipo" -> jdbc.update("UPDATE respostas_encerramento_recebidas SET event_type = 'OUTRO'");
                case "versao" -> jdbc.update("UPDATE respostas_encerramento_recebidas SET event_version = 2");
                case "correlacao" -> jdbc.update(
                        "UPDATE respostas_encerramento_recebidas SET correlation_id = ?", outra.transactionId());
                case "nano_negativo" -> jdbc.update("UPDATE respostas_encerramento_recebidas SET occurred_nano = -1");
                case "nano_alto" -> jdbc.update("UPDATE respostas_encerramento_recebidas SET occurred_nano = 1000000000");
                case "resultado" -> jdbc.update("UPDATE respostas_encerramento_recebidas SET resultado = 'OUTRO'");
                case "saida_extra" -> jdbc.update(
                        "UPDATE respostas_encerramento_recebidas SET output_event_id = ?", UUID.randomUUID());
                case "saida_ausente" -> jdbc.update("UPDATE respostas_encerramento_recebidas SET output_event_id = NULL");
                default -> throw new IllegalArgumentException(campo);
            }
        });
        var constraint = switch (campo) {
            case "tipo", "versao" -> "ck_resposta_encerramento_tipo";
            case "correlacao" -> "ck_resposta_encerramento_correlacao";
            case "nano_negativo", "nano_alto" -> "ck_resposta_encerramento_nano";
            case "resultado", "saida_extra", "saida_ausente" -> "ck_resposta_encerramento_resultado";
            default -> "fk_resposta_encerramento_pedido";
        };
        assertViolacao(falha, constraint.startsWith("fk_") ? "23503" : "23514", constraint);

        assertThat(jdbc.queryForMap("SELECT * FROM respostas_encerramento_recebidas")).isEqualTo(antes);
        assertThat(respostas.buscarPorEvento(original.eventId())).contains(original);
    }

    @Test
    void banco_deveRecusarNovaRespostaParaPedidoJaRespondido() {
        var original = prepararResposta(RespostaEncerramentoRecebida.Resultado.CONFIRMADO);
        respostas.inserir(original);

        var falha = catchThrowable(() -> jdbc.update("""
                INSERT INTO respostas_encerramento_recebidas
                  (event_id, transaction_id, creation_event_id, event_type, event_version, correlation_id,
                   causation_id, occurred_epoch_second, occurred_nano, resultado, output_event_id)
                SELECT ?, transaction_id, creation_event_id, event_type, event_version, correlation_id,
                       causation_id, occurred_epoch_second, occurred_nano, resultado, output_event_id
                FROM respostas_encerramento_recebidas
                """, UUID.randomUUID()));
        assertViolacao(falha, "23505", "uq_resposta_encerramento_pedido");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM respostas_encerramento_recebidas", Integer.class))
                .isEqualTo(1);
        assertThat(respostas.buscarPorEvento(original.eventId())).contains(original);
    }

    @Test
    void banco_devePreservarEventoOriginal_quandoIdentidadeDuplicar() {
        var original = prepararResposta(RespostaEncerramentoRecebida.Resultado.CONFIRMADO);
        var outra = prepararResposta(RespostaEncerramentoRecebida.Resultado.CONFIRMADO);
        respostas.inserir(original);

        var divergente = new RespostaEncerramentoRecebida(original.eventId(), outra.transactionId(),
                outra.creationEventId(), outra.occurredAt(), outra.correlationId(), outra.causationId(),
                outra.resultado(), outra.outputEventId());
        var falha = catchThrowable(() -> respostas.inserir(divergente));
        assertViolacao(falha, "23505", "respostas_encerramento_recebidas_pkey");

        assertThat(respostas.buscarPorEvento(original.eventId())).contains(original);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM respostas_encerramento_recebidas", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void inserir_deveParticiparDoRollbackExterno_ePermitirNovaTentativa() {
        var original = prepararResposta(RespostaEncerramentoRecebida.Resultado.CONFIRMADO);
        var transacao = jdbc.queryForMap("SELECT * FROM transacoes");
        var pedido = jdbc.queryForMap("SELECT * FROM pedidos_encerramento");
        var tx = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> tx.execute(status -> {
            respostas.inserir(original);
            assertThat(respostas.buscarPorEvento(original.eventId())).contains(original);
            throw new IllegalStateException("falha após recibo real");
        })).isInstanceOf(IllegalStateException.class).hasMessage("falha após recibo real");

        assertThat(respostas.buscarPorEvento(original.eventId())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM respostas_encerramento_recebidas", Integer.class)).isZero();
        assertThat(jdbc.queryForMap("SELECT * FROM transacoes")).isEqualTo(transacao);
        assertThat(jdbc.queryForMap("SELECT * FROM pedidos_encerramento")).isEqualTo(pedido);
        assertThat(jdbc.queryForList("SELECT * FROM historico_transacoes")).isEmpty();
        respostas.inserir(original);
        assertThat(respostas.buscarPorEvento(original.eventId())).contains(original);
    }

    private RespostaEncerramentoRecebida prepararResposta(RespostaEncerramentoRecebida.Resultado resultado) {
        var transacao = criar.executar(UUID.randomUUID(), new BigDecimal("75.00"), Currency.getInstance("BRL"));
        var pedido = solicitar.executar(UUID.randomUUID(), transacao.id(), UUID.randomUUID());
        return new RespostaEncerramentoRecebida(UUID.randomUUID(), transacao.id(), pedido.creationEventId(),
                Instant.parse("2026-10-14T18:00:00.123456789Z"), transacao.id(), pedido.eventId(), resultado,
                resultado == RespostaEncerramentoRecebida.Resultado.DECISAO_EXISTENTE
                        ? Optional.of(UUID.randomUUID()) : Optional.empty());
    }

    private void assertViolacao(Throwable falha, String sqlState, String constraint) {
        assertThat(falha).isInstanceOf(DataIntegrityViolationException.class);
        var causa = ((DataIntegrityViolationException) falha).getMostSpecificCause();
        assertThat(causa).isInstanceOf(PSQLException.class);
        var postgres = (PSQLException) causa;
        assertThat(postgres.getSQLState()).isEqualTo(sqlState);
        assertThat(postgres.getServerErrorMessage().getConstraint()).isEqualTo(constraint);
    }
}
