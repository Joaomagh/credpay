package br.com.credpay.transacoes.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
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
    static final PostgreSQLContainer<?> POSTGRES = new PostgresDaFixture()
            .withDatabaseName("credpay_resposta_encerramento").withUsername("test").withPassword("test");

    private static final class PostgresDaFixture extends PostgreSQLContainer<PostgresDaFixture> {
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

    @BeforeEach void preparar() { poolDaFixture = dataSource; }

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
        assertThat(jdbc.queryForMap("SELECT * FROM transacoes WHERE id = ?", original.id())).isEqualTo(transacao);
        assertThat(jdbc.queryForList("SELECT * FROM historico_transacoes WHERE transaction_id = ?", original.id()))
                .isEqualTo(historico);
        assertThat(jdbc.queryForMap("SELECT * FROM pedidos_encerramento WHERE event_id = ?", pedido.eventId()))
                .isEqualTo(snapshotPedido);
        assertThat(criar.executar(chave, new BigDecimal("75.0"), Currency.getInstance("BRL"))).isEqualTo(original);
    }
}
