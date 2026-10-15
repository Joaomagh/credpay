package br.com.credpay.processamento.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Stream;
import tools.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.postgresql.util.PSQLException;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
class EncerramentoMigrationIntegrationTest {
    private static final UUID PEDIDO = UUID.fromString("613bd312-a432-4eac-a71a-7d6434cb5ce3");
    private static final UUID TRANSACAO = UUID.fromString("96cb62d8-c735-47fb-9a0e-3b1ac4e4cde4");
    private static final UUID CRIACAO = UUID.fromString("5ba9d9fc-652b-45c9-a7f5-47f8d3044429");
    private static final UUID RESPOSTA = UUID.fromString("a6a0fd63-1d9a-4f3a-a60f-ea370e674a7c");
    private static final UUID EVIDENCIA = UUID.fromString("9704dff4-c778-4f1b-bf84-95025945d6b4");
    private static final UUID SAIDA_FINANCEIRA = UUID.fromString("b6f69b53-a295-48fa-8138-84883e3b5578");
    private static final UUID OUTRA_IDENTIDADE = UUID.fromString("04c58b10-bceb-4aef-9637-65fca5f733fd");
    private static final Instant PEDIDO_EM = Instant.parse("2026-10-14T12:00:00.123456789Z");
    private static final Instant RESPOSTA_EM = Instant.parse("2026-10-14T12:00:01.987654Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            "postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0")
            .withDatabaseName("credpay_encerramento_migration")
            .withUsername("test").withPassword("test");

    @BeforeAll
    static void migrar() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration").load().migrate();
    }

    @BeforeEach
    void limparSomenteBancoDescartavelDaFixture() throws Exception {
        try (var conexao = conectar(); var comando = conexao.createStatement()) {
            comando.execute("TRUNCATE TABLE criacao_bloqueada_recebimentos, outbox_encerramentos, encerramentos, outbox_eventos, processamentos");
        }
    }

    @Test
    void persistir_deveConservarPedidoERespostaNaOutboxPropria() throws Exception {
        gravarERelerPedidoEResposta();
    }

    private void gravarERelerPedidoEResposta() throws Exception {
        String payload = payloadResposta("CONFIRMADO", null, 1);
        try (var conexao = conectar()) {
            conexao.setAutoCommit(false);
            inserirPedido(conexao);
            inserirIntencao(conexao, payload, 1);
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

    @ParameterizedTest
    @EnumSource(IdentidadeUnica.class)
    void persistir_deveRecusarIdentidadeDuplicadaSemSubstituirOriginal(IdentidadeUnica campo) throws Exception {
        gravarERelerPedidoEResposta();
        var outroPedido = UUID.fromString("cb762331-c013-4dc8-a8b7-0eb40810a3cf");
        var outraTransacao = UUID.fromString("ea352857-d34e-49a4-bfb9-9856be519582");
        var outraResposta = UUID.fromString("68a98549-6a11-4097-b945-b9fb99d7d4c");
        try (var conexao = conectar()) {
            var falha = catchThrowable(() -> {
                if (campo == IdentidadeUnica.OUTBOX_EVENTO || campo == IdentidadeUnica.OUTBOX_TRANSACAO) {
                    copiarPedido(conexao, outroPedido, outraTransacao, outraResposta);
                    try (var inserir = conexao.prepareStatement("""
                            INSERT INTO outbox_encerramentos
                                (event_id, aggregate_id, event_type, event_version, payload, occurred_at)
                            SELECT ?, ?, event_type, event_version, payload, occurred_at FROM outbox_encerramentos
                            """)) {
                        inserir.setObject(1, campo == IdentidadeUnica.OUTBOX_EVENTO ? RESPOSTA : outraResposta);
                        inserir.setObject(2, campo == IdentidadeUnica.OUTBOX_TRANSACAO ? TRANSACAO : outraTransacao);
                        inserir.executeUpdate();
                    }
                } else {
                    copiarPedido(conexao, campo == IdentidadeUnica.PEDIDO ? PEDIDO : outroPedido,
                            campo == IdentidadeUnica.TRANSACAO ? TRANSACAO : outraTransacao,
                            campo == IdentidadeUnica.RESPOSTA ? RESPOSTA : outraResposta);
                }
            });
            assertThat(falha).withFailMessage("identidade duplicada foi aceita ou falhou fora do PostgreSQL")
                    .isInstanceOf(PSQLException.class);
            var erro = (PSQLException) falha;
            assertThat(erro.getSQLState()).withFailMessage("duplicata não falhou por unicidade").isEqualTo("23505");
            assertThat(erro.getServerErrorMessage().getConstraint())
                    .withFailMessage("duplicata não atingiu sua constraint de identidade").isEqualTo(campo.constraint);
        }
        try (var conexao = conectar(); var consultar = conexao.prepareStatement(
                "SELECT response_event_id, resultado, evidence_id FROM encerramentos WHERE request_event_id = ?")) {
            consultar.setObject(1, PEDIDO);
            try (var linhas = consultar.executeQuery()) {
                assertThat(linhas.next()).isTrue();
                assertThat(linhas.getObject("response_event_id", UUID.class)).isEqualTo(RESPOSTA);
                assertThat(linhas.getString("resultado")).isEqualTo("CONFIRMADO");
                assertThat(linhas.getObject("evidence_id", UUID.class)).isEqualTo(EVIDENCIA);
                assertThat(linhas.next()).isFalse();
            }
        }
    }

    private void copiarPedido(Connection conexao, UUID pedido, UUID transacao, UUID resposta) throws Exception {
        try (var inserir = conexao.prepareStatement("""
                INSERT INTO encerramentos (request_event_id, transaction_id, creation_event_id, event_type,
                    event_version, request_occurred_epoch_second, request_occurred_nano, correlation_id,
                    motivo, evidence_id, resultado, response_event_id, responded_at, output_event_id)
                SELECT ?, ?, creation_event_id, event_type, event_version, request_occurred_epoch_second,
                    request_occurred_nano, ?, motivo, evidence_id, resultado, ?, responded_at, output_event_id
                FROM encerramentos WHERE request_event_id = ?
                """)) {
            inserir.setObject(1, pedido);
            inserir.setObject(2, transacao);
            inserir.setObject(3, transacao);
            inserir.setObject(4, resposta);
            inserir.setObject(5, PEDIDO);
            inserir.executeUpdate();
        }
    }

    enum IdentidadeUnica {
        PEDIDO("pk_encerramentos"), TRANSACAO("uq_encerramentos_transaction_id"),
        RESPOSTA("uq_encerramentos_response_event_id"), OUTBOX_EVENTO("pk_outbox_encerramentos"),
        OUTBOX_TRANSACAO("uq_outbox_encerramentos_aggregate_id");

        private final String constraint;
        IdentidadeUnica(String constraint) { this.constraint = constraint; }
    }

    @ParameterizedTest
    @MethodSource("camposObrigatorios")
    void persistir_deveRecusarCampoAusenteSemAlterarOriginal(String tabela, String coluna) throws Exception {
        gravarERelerPedidoEResposta();
        try (var conexao = conectar(); var comando = conexao.createStatement()) {
            Object original;
            try (var linhas = comando.executeQuery("SELECT " + coluna + " FROM " + tabela)) {
                assertThat(linhas.next()).isTrue();
                original = linhas.getObject(1);
            }
            var falha = catchThrowable(() -> comando.executeUpdate("UPDATE " + tabela + " SET " + coluna + " = NULL"));
            assertThat(falha).withFailMessage("campo obrigatório foi aceito ou falhou fora do PostgreSQL")
                    .isInstanceOf(PSQLException.class);
            var erro = (PSQLException) falha;
            assertThat(erro.getSQLState()).withFailMessage("ausência não falhou por NOT NULL").isEqualTo("23502");
            assertThat(erro.getServerErrorMessage().getColumn())
                    .withFailMessage("NOT NULL não atingiu a coluna esperada").isEqualTo(coluna);
            try (var linhas = comando.executeQuery("SELECT " + coluna + " FROM " + tabela)) {
                assertThat(linhas.next()).isTrue();
                assertThat(linhas.getObject(1)).withFailMessage("campo obrigatório original foi alterado").isEqualTo(original);
            }
        }
    }

    private static Stream<Arguments> camposObrigatorios() {
        return Stream.concat(Stream.of("request_event_id", "transaction_id", "creation_event_id", "event_type",
                        "event_version", "request_occurred_epoch_second", "request_occurred_nano", "correlation_id",
                        "motivo", "evidence_id", "resultado", "response_event_id", "responded_at")
                .map(coluna -> Arguments.of("encerramentos", coluna)),
                Stream.of("event_id", "aggregate_id", "event_type", "event_version", "payload", "occurred_at")
                        .map(coluna -> Arguments.of("outbox_encerramentos", coluna)));
    }

    @ParameterizedTest
    @EnumSource(EnvelopeInvalido.class)
    void persistir_deveRecusarEnvelopeInvalidoSemAlterarOriginal(EnvelopeInvalido caso) throws Exception {
        gravarERelerPedidoEResposta();
        try (var conexao = conectar(); var comando = conexao.createStatement()) {
            var pedidoOriginal = snapshot(conexao, "encerramentos");
            var outboxOriginal = snapshot(conexao, "outbox_encerramentos");
            var falha = catchThrowable(() -> comando.executeUpdate(caso.sql));
            assertThat(falha).withFailMessage("envelope inválido foi aceito ou falhou fora do PostgreSQL")
                    .isInstanceOf(PSQLException.class);
            var erro = (PSQLException) falha;
            assertThat(erro.getSQLState()).withFailMessage("envelope não falhou por CHECK").isEqualTo("23514");
            assertThat(erro.getServerErrorMessage().getConstraint())
                    .withFailMessage("envelope não atingiu a constraint esperada").isEqualTo(caso.constraint);
            assertThat(snapshot(conexao, "encerramentos")).withFailMessage("pedido original foi alterado").isEqualTo(pedidoOriginal);
            assertThat(snapshot(conexao, "outbox_encerramentos")).withFailMessage("outbox original foi alterada").isEqualTo(outboxOriginal);
        }
    }

    private Map<String, Object> snapshot(Connection conexao, String tabela) throws Exception {
        try (var comando = conexao.createStatement(); var linhas = comando.executeQuery("SELECT * FROM " + tabela)) {
            assertThat(linhas.next()).withFailMessage("snapshot não encontrou o registro esperado").isTrue();
            var dados = new LinkedHashMap<String, Object>();
            var metadata = linhas.getMetaData();
            for (int coluna = 1; coluna <= metadata.getColumnCount(); coluna++) {
                dados.put(metadata.getColumnLabel(coluna), linhas.getObject(coluna));
            }
            assertThat(linhas.next()).withFailMessage("snapshot encontrou registro adicional").isFalse();
            return dados;
        }
    }

    enum EnvelopeInvalido {
        TIPO_PEDIDO("UPDATE encerramentos SET event_type = 'Outro'", "ck_encerramentos_event_type"),
        VERSAO_PEDIDO("UPDATE encerramentos SET event_version = 2", "ck_encerramentos_event_version"),
        NANO_NEGATIVO("UPDATE encerramentos SET request_occurred_nano = -1", "ck_encerramentos_occurred_nano"),
        NANO_SUPERIOR("UPDATE encerramentos SET request_occurred_nano = 1000000000", "ck_encerramentos_occurred_nano"),
        CORRELACAO("UPDATE encerramentos SET correlation_id = '04c58b10-bceb-4aef-9637-65fca5f733fd'", "ck_encerramentos_correlation"),
        MOTIVO("UPDATE encerramentos SET motivo = 'OUTRO'", "ck_encerramentos_motivo"),
        RESULTADO("UPDATE encerramentos SET resultado = 'OUTRO'", "ck_encerramentos_resultado_saida"),
        CONFIRMADO_COM_SAIDA("UPDATE encerramentos SET output_event_id = '04c58b10-bceb-4aef-9637-65fca5f733fd'", "ck_encerramentos_resultado_saida"),
        DECISAO_SEM_SAIDA("UPDATE encerramentos SET resultado = 'DECISAO_EXISTENTE'", "ck_encerramentos_resultado_saida"),
        TIPO_OUTBOX("UPDATE outbox_encerramentos SET event_type = 'Outro'", "ck_outbox_encerramentos_event_type"),
        VERSAO_OUTBOX("UPDATE outbox_encerramentos SET event_version = 2", "ck_outbox_encerramentos_event_version"),
        PAYLOAD_ARRAY("UPDATE outbox_encerramentos SET payload = '[]'::jsonb", "ck_outbox_encerramentos_payload_object"),
        PAYLOAD_JSON_NULL("UPDATE outbox_encerramentos SET payload = 'null'::jsonb", "ck_outbox_encerramentos_payload_object");

        private final String sql;
        private final String constraint;
        EnvelopeInvalido(String sql, String constraint) { this.sql = sql; this.constraint = constraint; }
    }

    @ParameterizedTest
    @EnumSource(VinculoInvalido.class)
    void persistir_deveRecusarVinculoDivergenteSemAlterarOriginal(VinculoInvalido caso) throws Exception {
        gravarERelerPedidoEResposta();
        try (var conexao = conectar(); var comando = conexao.createStatement()) {
            if (caso == VinculoInvalido.TRANSACAO_FINANCEIRA || caso == VinculoInvalido.CRIACAO_FINANCEIRA) {
                inserirDecisaoFinanceira(conexao, caso == VinculoInvalido.TRANSACAO_FINANCEIRA ? OUTRA_IDENTIDADE : TRANSACAO,
                        caso == VinculoInvalido.CRIACAO_FINANCEIRA ? OUTRA_IDENTIDADE : CRIACAO);
            }
            var pedidoOriginal = snapshot(conexao, "encerramentos");
            var outboxOriginal = snapshot(conexao, "outbox_encerramentos");
            var falha = catchThrowable(() -> {
                if (caso == VinculoInvalido.RESPOSTA_OUTBOX || caso == VinculoInvalido.TRANSACAO_OUTBOX) {
                    comando.executeUpdate("UPDATE outbox_encerramentos SET "
                            + (caso == VinculoInvalido.RESPOSTA_OUTBOX ? "event_id" : "aggregate_id")
                            + " = '" + OUTRA_IDENTIDADE + "'");
                } else {
                    comando.executeUpdate("UPDATE encerramentos SET resultado = 'DECISAO_EXISTENTE', output_event_id = '"
                            + SAIDA_FINANCEIRA + "'");
                }
            });
            assertThat(falha).withFailMessage("vínculo inválido foi aceito ou falhou fora do PostgreSQL").isInstanceOf(PSQLException.class);
            var erro = (PSQLException) falha;
            assertThat(erro.getSQLState()).withFailMessage("vínculo não falhou por FK").isEqualTo("23503");
            assertThat(erro.getServerErrorMessage().getConstraint())
                    .withFailMessage("vínculo não atingiu a FK esperada").isEqualTo(caso.constraint);
            assertThat(snapshot(conexao, "encerramentos")).withFailMessage("pedido original foi alterado").isEqualTo(pedidoOriginal);
            assertThat(snapshot(conexao, "outbox_encerramentos")).withFailMessage("outbox original foi alterada").isEqualTo(outboxOriginal);
        }
    }

    @Test
    void persistir_deveAceitarNegativa_quandoSaidaPertencerAMesmaTransacaoECriacao() throws Exception {
        try (var conexao = conectar()) {
            conexao.setAutoCommit(false);
            inserirDecisaoFinanceira(conexao, TRANSACAO, CRIACAO);
            inserirPedido(conexao, "DECISAO_EXISTENTE", SAIDA_FINANCEIRA);
            inserirIntencao(conexao, payloadResposta("DECISAO_EXISTENTE", SAIDA_FINANCEIRA, 1), 1);
            conexao.commit();
        }
        try (var conexao = conectar(); var comando = conexao.createStatement(); var linhas = comando.executeQuery("""
                SELECT e.resultado, e.output_event_id, p.resultado AS financeiro FROM encerramentos e
                JOIN processamentos p ON p.transaction_id = e.transaction_id AND p.event_id = e.creation_event_id
                    AND p.output_event_id = e.output_event_id
                """)) {
            assertThat(linhas.next()).isTrue();
            assertThat(linhas.getString("resultado")).isEqualTo("DECISAO_EXISTENTE");
            assertThat(linhas.getObject("output_event_id", UUID.class)).isEqualTo(SAIDA_FINANCEIRA);
            assertThat(linhas.getString("financeiro")).isEqualTo("APROVADA");
            assertThat(linhas.next()).isFalse();
        }
    }

    private void inserirDecisaoFinanceira(Connection conexao, UUID transacao, UUID criacao) throws Exception {
        try (var inserir = conexao.prepareStatement("""
                INSERT INTO processamentos (event_id, transaction_id, event_type, event_version,
                    event_occurred_epoch_second, event_occurred_nano, correlation_id, input_status,
                    valor, moeda, limite_aplicado, resultado, processed_at, output_event_id)
                VALUES (?, ?, 'TransacaoCriada', 1, ?, ?, ?, 'PENDENTE', 50.00, 'BRL', 100.00, 'APROVADA', ?, ?)
                """)) {
            inserir.setObject(1, criacao);
            inserir.setObject(2, transacao);
            inserir.setLong(3, PEDIDO_EM.getEpochSecond());
            inserir.setInt(4, PEDIDO_EM.getNano());
            inserir.setObject(5, transacao);
            inserir.setTimestamp(6, Timestamp.from(RESPOSTA_EM));
            inserir.setObject(7, SAIDA_FINANCEIRA);
            assertThat(inserir.executeUpdate()).isEqualTo(1);
        }
    }

    enum VinculoInvalido {
        RESPOSTA_OUTBOX("fk_outbox_encerramentos_response"), TRANSACAO_OUTBOX("fk_outbox_encerramentos_response"),
        SAIDA_FINANCEIRA_AUSENTE("fk_encerramentos_financial_decision"),
        TRANSACAO_FINANCEIRA("fk_encerramentos_financial_decision"), CRIACAO_FINANCEIRA("fk_encerramentos_financial_decision");

        private final String constraint;
        VinculoInvalido(String constraint) { this.constraint = constraint; }
    }

    @Test
    void reverter_deveRemoverPrimeiraEscrita_quandoIntencaoFalhar() throws Exception {
        try (var conexao = conectar()) {
            conexao.setAutoCommit(false);
            inserirPedido(conexao);
            var falha = catchThrowable(() -> inserirIntencao(conexao, payloadResposta("CONFIRMADO", null, 2), 2));
            assertThat(falha).withFailMessage("segunda escrita não falhou no PostgreSQL").isInstanceOf(PSQLException.class);
            var erro = (PSQLException) falha;
            assertThat(erro.getSQLState()).withFailMessage("segunda escrita não falhou por CHECK").isEqualTo("23514");
            assertThat(erro.getServerErrorMessage().getConstraint())
                    .withFailMessage("segunda escrita não atingiu a constraint esperada").isEqualTo("ck_outbox_encerramentos_event_version");
            conexao.rollback();
        }
        try (var conexao = conectar(); var consultar = conexao.createStatement()) {
            for (var tabela : List.of("encerramentos", "outbox_encerramentos", "processamentos", "outbox_eventos")) {
                try (var linhas = consultar.executeQuery("SELECT COUNT(*) FROM " + tabela)) {
                    assertThat(linhas.next()).isTrue();
                    assertThat(linhas.getInt(1)).withFailMessage("rollback deixou registro parcial").isZero();
                }
            }
        }
    }

    private String payloadResposta(String resultado, UUID saidaFinanceira, int versao) throws Exception {
        var dados = new LinkedHashMap<String, Object>();
        dados.put("transactionId", TRANSACAO);
        dados.put("creationEventId", CRIACAO);
        dados.put("resultado", resultado);
        if (saidaFinanceira != null) dados.put("outputEventId", saidaFinanceira);
        return new ObjectMapper().writeValueAsString(Map.of(
                "eventId", RESPOSTA, "eventType", "EncerramentoTransacaoRespondido", "eventVersion", versao,
                "occurredAt", RESPOSTA_EM.toString(), "correlationId", TRANSACAO, "causationId", PEDIDO, "data", dados));
    }

    private void inserirIntencao(Connection conexao, String payload, int versao) throws Exception {
        try (var inserir = conexao.prepareStatement("""
                INSERT INTO outbox_encerramentos (event_id, aggregate_id, event_type, event_version, payload, occurred_at)
                VALUES (?, ?, 'EncerramentoTransacaoRespondido', ?, CAST(? AS jsonb), ?)
                """)) {
            inserir.setObject(1, RESPOSTA);
            inserir.setObject(2, TRANSACAO);
            inserir.setInt(3, versao);
            inserir.setString(4, payload);
            inserir.setTimestamp(5, Timestamp.from(RESPOSTA_EM));
            assertThat(inserir.executeUpdate()).isEqualTo(1);
        }
    }

    private void inserirPedido(Connection conexao) throws Exception {
        inserirPedido(conexao, "CONFIRMADO", null);
    }

    private void inserirPedido(Connection conexao, String resultado, UUID saidaFinanceira) throws Exception {
        try (var inserir = conexao.prepareStatement("""
                INSERT INTO encerramentos (request_event_id, transaction_id, creation_event_id,
                    event_type, event_version, request_occurred_epoch_second, request_occurred_nano,
                    correlation_id, motivo, evidence_id, resultado, response_event_id, responded_at, output_event_id)
                VALUES (?, ?, ?, 'EncerramentoTransacaoSolicitado', 1, ?, ?, ?,
                    'FALHA_PERMANENTE_CONTROLADA', ?, ?, ?, ?, ?)
                """)) {
            inserir.setObject(1, PEDIDO);
            inserir.setObject(2, TRANSACAO);
            inserir.setObject(3, CRIACAO);
            inserir.setLong(4, PEDIDO_EM.getEpochSecond());
            inserir.setInt(5, PEDIDO_EM.getNano());
            inserir.setObject(6, TRANSACAO);
            inserir.setObject(7, EVIDENCIA);
            inserir.setString(8, resultado);
            inserir.setObject(9, RESPOSTA);
            inserir.setTimestamp(10, Timestamp.from(RESPOSTA_EM));
            inserir.setObject(11, saidaFinanceira);
            assertThat(inserir.executeUpdate()).isEqualTo(1);
        }
    }

    private Connection conectar() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
