package br.com.credpay.processamento.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class EncerramentoMigrationIntegrationTest {
    private static final UUID PEDIDO = UUID.fromString("613bd312-a432-4eac-a71a-7d6434cb5ce3");
    private static final UUID TRANSACAO = UUID.fromString("96cb62d8-c735-47fb-9a0e-3b1ac4e4cde4");
    private static final UUID CRIACAO = UUID.fromString("5ba9d9fc-652b-45c9-a7f5-47f8d3044429");
    private static final UUID RESPOSTA = UUID.fromString("a6a0fd63-1d9a-4f3a-a60f-ea370e674a7c");
    private static final UUID EVIDENCIA = UUID.fromString("9704dff4-c778-4f1b-bf84-95025945d6b4");
    private static final Instant PEDIDO_EM = Instant.parse("2026-10-14T12:00:00.123456789Z");
    private static final Instant RESPOSTA_EM = Instant.parse("2026-10-14T12:00:01.987654Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            "postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0")
            .withDatabaseName("credpay_encerramento_migration")
            .withUsername("test").withPassword("test");

    @BeforeAll
    static void migrar() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
    }

    @Test
    void persistir_deveConservarPedidoERespostaNaOutboxPropria() throws Exception {
        String payload = new ObjectMapper().writeValueAsString(java.util.Map.of(
                "eventId", RESPOSTA, "eventType", "EncerramentoTransacaoRespondido", "eventVersion", 1,
                "occurredAt", RESPOSTA_EM.toString(), "correlationId", TRANSACAO, "causationId", PEDIDO,
                "data", java.util.Map.of("transactionId", TRANSACAO, "creationEventId", CRIACAO, "resultado", "CONFIRMADO")));
        try (var conexao = conectar()) {
            conexao.setAutoCommit(false);
            inserirPedido(conexao);
            try (var inserir = conexao.prepareStatement("""
                    INSERT INTO outbox_encerramentos (event_id, aggregate_id, event_type, event_version, payload, occurred_at)
                    VALUES (?, ?, 'EncerramentoTransacaoRespondido', 1, CAST(? AS jsonb), ?)
                    """)) {
                inserir.setObject(1, RESPOSTA);
                inserir.setObject(2, TRANSACAO);
                inserir.setString(3, payload);
                inserir.setTimestamp(4, Timestamp.from(RESPOSTA_EM));
                assertThat(inserir.executeUpdate()).isEqualTo(1);
            }
            conexao.commit();
        }
        try (var conexao = conectar(); var consultar = conexao.createStatement();
             var linhas = consultar.executeQuery("""
                     SELECT e.*, o.aggregate_id AS outbox_transaction_id, o.payload::text, o.occurred_at, o.published_at
                     FROM encerramentos e JOIN outbox_encerramentos o ON o.event_id = e.response_event_id
                     """)) {
            assertThat(linhas.next()).isTrue();
            assertThat(linhas.getObject("request_event_id", UUID.class)).isEqualTo(PEDIDO);
            assertThat(linhas.getObject("transaction_id", UUID.class)).isEqualTo(TRANSACAO);
            assertThat(linhas.getObject("creation_event_id", UUID.class)).isEqualTo(CRIACAO);
            assertThat(linhas.getString("event_type")).isEqualTo("EncerramentoTransacaoSolicitado");
            assertThat(linhas.getInt("event_version")).isEqualTo(1);
            assertThat(Instant.ofEpochSecond(linhas.getLong("request_occurred_epoch_second"),
                    linhas.getInt("request_occurred_nano"))).isEqualTo(PEDIDO_EM);
            assertThat(linhas.getObject("correlation_id", UUID.class)).isEqualTo(TRANSACAO);
            assertThat(linhas.getString("motivo")).isEqualTo("FALHA_PERMANENTE_CONTROLADA");
            assertThat(linhas.getObject("evidence_id", UUID.class)).isEqualTo(EVIDENCIA);
            assertThat(linhas.getString("resultado")).isEqualTo("CONFIRMADO");
            assertThat(linhas.getObject("response_event_id", UUID.class)).isEqualTo(RESPOSTA);
            assertThat(linhas.getObject("outbox_transaction_id", UUID.class)).isEqualTo(TRANSACAO);
            assertThat(linhas.getObject("output_event_id")).isNull();
            assertThat(linhas.getTimestamp("responded_at").toInstant()).isEqualTo(RESPOSTA_EM);
            assertThat(linhas.getTimestamp("occurred_at").toInstant()).isEqualTo(RESPOSTA_EM);
            assertThat(linhas.getObject("published_at")).isNull();
            assertThat(new ObjectMapper().readTree(linhas.getString("payload")))
                    .withFailMessage("outbox de encerramento perdeu o envelope original")
                    .isEqualTo(new ObjectMapper().readTree(payload));
            assertThat(linhas.next()).isFalse();
        }
        try (var conexao = conectar(); var consultar = conexao.createStatement();
             var linhas = consultar.executeQuery("SELECT COUNT(*) FROM outbox_eventos")) {
            assertThat(linhas.next()).isTrue();
            assertThat(linhas.getInt(1)).isZero();
        }
    }

    private void inserirPedido(Connection conexao) throws Exception {
        try (var inserir = conexao.prepareStatement("""
                INSERT INTO encerramentos (request_event_id, transaction_id, creation_event_id,
                    event_type, event_version, request_occurred_epoch_second, request_occurred_nano,
                    correlation_id, motivo, evidence_id, resultado, response_event_id, responded_at)
                VALUES (?, ?, ?, 'EncerramentoTransacaoSolicitado', 1, ?, ?, ?,
                    'FALHA_PERMANENTE_CONTROLADA', ?, 'CONFIRMADO', ?, ?)
                """)) {
            inserir.setObject(1, PEDIDO);
            inserir.setObject(2, TRANSACAO);
            inserir.setObject(3, CRIACAO);
            inserir.setLong(4, PEDIDO_EM.getEpochSecond());
            inserir.setInt(5, PEDIDO_EM.getNano());
            inserir.setObject(6, TRANSACAO);
            inserir.setObject(7, EVIDENCIA);
            inserir.setObject(8, RESPOSTA);
            inserir.setTimestamp(9, Timestamp.from(RESPOSTA_EM));
            assertThat(inserir.executeUpdate()).isEqualTo(1);
        }
    }

    private Connection conectar() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
