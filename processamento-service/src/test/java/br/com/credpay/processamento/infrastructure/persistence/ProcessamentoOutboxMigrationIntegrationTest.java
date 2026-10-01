package br.com.credpay.processamento.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class ProcessamentoOutboxMigrationIntegrationTest {

    private static final UUID EVENTO_ENTRADA = UUID.fromString("6dc8d48d-5b20-4ee9-ac7e-832e421121aa");
    private static final UUID TRANSACAO = UUID.fromString("7b8b61c2-9f63-4d74-9f8d-89cb52de0ed9");
    private static final UUID EVENTO_SAIDA = UUID.fromString("42a06a3b-178b-49db-a08e-1f7dad9ebc38");
    private static final Instant PROCESSADO_EM = Instant.parse("2026-09-16T12:00:01.123456Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            "postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0")
            .withDatabaseName("credpay_processamento_migration")
            .withUsername("test")
            .withPassword("test");

    @Test
    void migrar_deveCriarIntencaoComMesmaIdentidade_quandoV1ContiverResultado() throws Exception {
        flywayV1().migrate();
        inserirResultadoV1();

        flywayAtual().migrate();

        try (var conexao = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var consulta = conexao.prepareStatement("""
                     SELECT event_id, aggregate_id, event_type, event_version,
                            payload::text, occurred_at, published_at
                     FROM outbox_eventos
                     """);
             var registros = consulta.executeQuery()) {
            assertThat(registros.next()).isTrue();
            assertThat(registros.getObject("event_id", UUID.class)).isEqualTo(EVENTO_SAIDA);
            assertThat(registros.getObject("aggregate_id", UUID.class)).isEqualTo(TRANSACAO);
            assertThat(registros.getString("event_type")).isEqualTo("TransacaoProcessada");
            assertThat(registros.getInt("event_version")).isEqualTo(1);
            assertThat(registros.getTimestamp("occurred_at").toInstant()).isEqualTo(PROCESSADO_EM);
            assertThat(registros.getTimestamp("published_at")).isNull();
            var payload = new ObjectMapper().readTree(registros.getString("payload"));
            assertThat(payload.path("eventId").asText()).isEqualTo(EVENTO_SAIDA.toString());
            assertThat(payload.path("eventType").asText()).isEqualTo("TransacaoProcessada");
            assertThat(payload.path("eventVersion").asInt()).isEqualTo(1);
            assertThat(Instant.parse(payload.path("occurredAt").asText())).isEqualTo(PROCESSADO_EM);
            assertThat(payload.path("correlationId").asText()).isEqualTo(TRANSACAO.toString());
            assertThat(payload.path("causationId").asText()).isEqualTo(EVENTO_ENTRADA.toString());
            assertThat(payload.path("data").path("transactionId").asText()).isEqualTo(TRANSACAO.toString());
            assertThat(payload.path("data").path("status").asText()).isEqualTo("APROVADA");
            assertThat(payload.path("data").size()).isEqualTo(2);
            assertThat(registros.next()).isFalse();
        }
    }

    private void inserirResultadoV1() throws Exception {
        try (var conexao = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var insercao = conexao.prepareStatement("""
                     INSERT INTO processamentos (
                         event_id, transaction_id, event_type, event_version,
                         event_occurred_epoch_second, event_occurred_nano, correlation_id,
                         input_status, valor, moeda, limite_aplicado, resultado,
                         processed_at, output_event_id
                     ) VALUES (?, ?, 'TransacaoCriada', 1, ?, ?, ?, 'PENDENTE',
                               ?, 'BRL', ?, 'APROVADA', ?, ?)
                     """)) {
            var ocorreuEm = Instant.parse("2026-09-16T12:00:00.123456789Z");
            insercao.setObject(1, EVENTO_ENTRADA);
            insercao.setObject(2, TRANSACAO);
            insercao.setLong(3, ocorreuEm.getEpochSecond());
            insercao.setInt(4, ocorreuEm.getNano());
            insercao.setObject(5, TRANSACAO);
            insercao.setBigDecimal(6, new BigDecimal("75.00"));
            insercao.setBigDecimal(7, new BigDecimal("100.00"));
            insercao.setTimestamp(8, Timestamp.from(PROCESSADO_EM));
            insercao.setObject(9, EVENTO_SAIDA);
            assertThat(insercao.executeUpdate()).isEqualTo(1);
        }
    }

    private Flyway flywayV1() {
        return Flyway.configure().dataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("1"))
                .load();
    }

    private Flyway flywayAtual() {
        return Flyway.configure().dataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load();
    }
}
