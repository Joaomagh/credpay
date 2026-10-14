package br.com.credpay.transacoes.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class EncerramentoConfirmadoMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            "postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0")
            .withDatabaseName("credpay_upgrade_encerramento").withUsername("test").withPassword("test");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void prepararVersaoAnteriorComFinanceiroPersistido() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").target(MigrationVersion.fromVersion("7")).load().migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
        inserirFinanceiro("APROVADA");
        inserirFinanceiro("REJEITADA");
    }

    @Test
    void migrar_deveConservarFinanceiroExistente_eAceitarInsertAnteriorSemColunasNovas() {
        var transacoes = jdbc.queryForList("SELECT * FROM transacoes ORDER BY id");
        var criacoes = jdbc.queryForList("SELECT * FROM outbox_eventos ORDER BY event_id");
        var antes = jdbc.queryForList("SELECT * FROM historico_transacoes ORDER BY event_id");
        var atual = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load();

        atual.migrate();

        assertThat(atual.info().current().getVersion().getVersion()).isEqualTo("8");
        assertThat(jdbc.queryForList("SELECT * FROM transacoes ORDER BY id")).isEqualTo(transacoes);
        assertThat(jdbc.queryForList("SELECT * FROM outbox_eventos ORDER BY event_id")).isEqualTo(criacoes);
        var depois = jdbc.queryForList("SELECT * FROM historico_transacoes ORDER BY event_id");
        assertThat(depois).hasSameSizeAs(antes);
        for (int indice = 0; indice < antes.size(); indice++) {
            assertThat(depois.get(indice)).containsAllEntriesOf(antes.get(indice))
                    .containsEntry("causa_criacao_id", antes.get(indice).get("causation_id"))
                    .containsEntry("causa_pedido_id", null).containsEntry("resultado_resposta", null);
        }
        var nova = inserirFinanceiro("REJEITADA");
        var registro = jdbc.queryForMap("SELECT * FROM historico_transacoes WHERE transaction_id = ?", nova);
        assertThat(registro).containsEntry("causa_criacao_id", registro.get("causation_id"))
                .containsEntry("causa_pedido_id", null).containsEntry("resultado_resposta", null);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM historico_transacoes", Integer.class)).isEqualTo(3);
    }

    private static UUID inserirFinanceiro(String estado) {
        var transacao = UUID.randomUUID();
        var criacao = UUID.randomUUID();
        var instante = Instant.parse("2026-10-14T18:00:00.123456789Z");
        jdbc.update("INSERT INTO transacoes (id, valor, moeda, status) VALUES (?, 123.450, 'BRL', ?)", transacao, estado);
        var payload = """
                {"eventId":"%s","eventType":"TransacaoCriada","eventVersion":1,"occurredAt":"%s",
                 "correlationId":"%s","data":{"transactionId":"%s","amount":"123.450",
                 "currency":"BRL","status":"PENDENTE"}}
                """.formatted(criacao, instante, transacao, transacao);
        jdbc.update("""
                INSERT INTO outbox_eventos (event_id, aggregate_id, event_type, event_version, payload, occurred_at)
                VALUES (?, ?, 'TransacaoCriada', 1, ?::jsonb, ?)
                """, criacao, transacao, payload, Timestamp.from(instante));
        jdbc.update("""
                INSERT INTO historico_transacoes
                  (event_id, transaction_id, event_type, event_version, correlation_id, causation_id,
                   estado_anterior, estado_final, origem, occurred_at_epoch_second, occurred_at_nano, aplicado_em)
                VALUES (?, ?, 'TransacaoProcessada', 1, ?, ?, 'PENDENTE', ?,
                        'processamento-service/TransacaoProcessada.v1', ?, ?, ?)
                """, UUID.randomUUID(), transacao, transacao, criacao, estado, instante.getEpochSecond(),
                instante.getNano(), Timestamp.from(Instant.parse("2026-10-14T18:30:00.987654Z")));
        return transacao;
    }
}
