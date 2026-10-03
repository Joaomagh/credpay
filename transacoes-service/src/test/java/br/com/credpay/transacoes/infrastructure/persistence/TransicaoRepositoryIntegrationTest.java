package br.com.credpay.transacoes.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.UUID;
import java.util.stream.Stream;

import br.com.credpay.transacoes.application.TransicaoRecebida;
import br.com.credpay.transacoes.application.TransicaoRepository;
import br.com.credpay.transacoes.domain.StatusTransacao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TransicaoRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            "postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0")
            .withDatabaseName("credpay_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void configurarBanco(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private TransicaoRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @ParameterizedTest
    @EnumSource(value = StatusTransacao.class, names = {"APROVADA", "REJEITADA"})
    void registrar_deveConfirmarStatusEHistoricoPreservandoDadosEInstantes(StatusTransacao resultado) {
        var transicao = preparar(resultado);

        repository.registrar(transicao);

        assertThat(repository.buscarPorEvento(transicao.eventId())).contains(transicao);
        assertThat(jdbc.queryForObject("SELECT status FROM transacoes WHERE id = ?",
                String.class, transicao.transactionId())).isEqualTo(resultado.name());
        assertThat(jdbc.queryForObject("SELECT valor FROM transacoes WHERE id = ?",
                BigDecimal.class, transicao.transactionId())).isEqualTo(new BigDecimal("123.450"));
        assertThat(jdbc.queryForObject("SELECT moeda FROM transacoes WHERE id = ?",
                String.class, transicao.transactionId())).isEqualTo(Currency.getInstance("BRL").getCurrencyCode());
    }

    @Test
    void registrar_deveReverterUpdate_quandoSegundaEscritaColidirComEventoExistente() {
        var original = preparar(StatusTransacao.APROVADA);
        repository.registrar(original);
        var outra = preparar(StatusTransacao.REJEITADA);
        var colisao = new TransicaoRecebida(original.eventId(), outra.transactionId(), outra.eventType(),
                outra.eventVersion(), outra.correlationId(), outra.causationId(), outra.estadoAnterior(),
                outra.estadoFinal(), outra.origem(), outra.occurredAt(), outra.aplicadoEm());

        assertThatThrownBy(() -> repository.registrar(colisao))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("historico_transacoes_pkey");

        assertThat(repository.buscarPorEvento(original.eventId())).contains(original);
        assertThat(jdbc.queryForObject("SELECT status FROM transacoes WHERE id = ?",
                String.class, outra.transactionId())).isEqualTo("PENDENTE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM historico_transacoes WHERE transaction_id = ?",
                Integer.class, outra.transactionId())).isZero();
    }

    @Test
    void banco_deveRecusarOutroEventoParaMesmaTransacao() {
        var original = preparar(StatusTransacao.APROVADA);
        repository.registrar(original);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO historico_transacoes
                SELECT ?, transaction_id, event_type, event_version, correlation_id, causation_id,
                       estado_anterior, estado_final, origem, occurred_at_epoch_second, occurred_at_nano, aplicado_em
                FROM historico_transacoes WHERE event_id = ?
                """, UUID.randomUUID(), original.eventId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("uq_historico_transacao");
        assertThat(repository.buscarPorEvento(original.eventId())).contains(original);
    }

    @Test
    void banco_deveRecusarCausaDeOutraTransacao() {
        var original = preparar(StatusTransacao.APROVADA);
        repository.registrar(original);
        var outra = preparar(StatusTransacao.REJEITADA);

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE historico_transacoes SET causation_id = ? WHERE event_id = ?
                """, outra.causationId(), original.eventId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("fk_historico_causa_transacao");
        assertThat(repository.buscarPorEvento(original.eventId())).contains(original);
    }

    @Test
    void banco_deveConservarTransacaoReferenciadaNoHistorico() {
        var original = preparar(StatusTransacao.APROVADA);
        repository.registrar(original);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM transacoes WHERE id = ?", original.transactionId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining("fk_historico_transacao");
        assertThat(repository.buscarPorEvento(original.eventId())).contains(original);
    }

    @ParameterizedTest
    @MethodSource("camposInvalidos")
    void banco_deveRecusarSemanticaInvalidaSemAlterarHistorico(String coluna, String expressao, String constraint) {
        var original = preparar(StatusTransacao.APROVADA);
        repository.registrar(original);

        // Column/expression come only from the fixed test cases below, never from external input.
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE historico_transacoes SET " + coluna + " = " + expressao + " WHERE event_id = ?",
                original.eventId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining(constraint);
        assertThat(repository.buscarPorEvento(original.eventId())).contains(original);
    }

    static Stream<Arguments> camposInvalidos() {
        return Stream.of(
                Arguments.of("event_type", "'Outro'", "ck_historico_tipo_versao"),
                Arguments.of("event_version", "2", "ck_historico_tipo_versao"),
                Arguments.of("correlation_id", "'9c29e2bb-c43d-41f2-94d2-37499799faca'::uuid", "ck_historico_correlacao"),
                Arguments.of("estado_anterior", "'APROVADA'", "ck_historico_estado_anterior"),
                Arguments.of("estado_final", "'PENDENTE'", "ck_historico_estado_final"),
                Arguments.of("estado_final", "'FALHOU'", "ck_historico_estado_final"),
                Arguments.of("origem", "'Outro'", "ck_historico_origem"),
                Arguments.of("occurred_at_nano", "-1", "ck_historico_nano"),
                Arguments.of("occurred_at_nano", "1000000000", "ck_historico_nano"));
    }

    @Test
    void registrar_deveRecusarTransacaoAusenteSemInventarHistorico() {
        var transicao = preparar(StatusTransacao.APROVADA);
        jdbc.update("DELETE FROM transacoes WHERE id = ?", transicao.transactionId());

        assertThatThrownBy(() -> repository.registrar(transicao))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("transição exige transação pendente e causa de criação local");

        assertThat(repository.buscarPorEvento(transicao.eventId())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transacoes WHERE id = ?",
                Integer.class, transicao.transactionId())).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = StatusTransacao.class, names = {"APROVADA", "REJEITADA"})
    void registrar_devePreservarEstadoFinalSemInventarHistorico(StatusTransacao estado) {
        var transicao = preparar(StatusTransacao.APROVADA);
        jdbc.update("UPDATE transacoes SET status = ? WHERE id = ?", estado.name(), transicao.transactionId());

        assertThatThrownBy(() -> repository.registrar(transicao))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("transição exige transação pendente e causa de criação local");

        assertThat(repository.buscarPorEvento(transicao.eventId())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM transacoes WHERE id = ?",
                String.class, transicao.transactionId())).isEqualTo(estado.name());
    }

    @ParameterizedTest
    @CsvSource({"Outro, 1", "TransacaoCriada, 2"})
    void registrar_deveRecusarCausaComTipoOuVersaoIncorreta(String tipo, int versao) {
        var transicao = preparar(StatusTransacao.APROVADA);
        jdbc.update("UPDATE outbox_eventos SET event_type = ?, event_version = ? WHERE event_id = ?",
                tipo, versao, transicao.causationId());

        assertThatThrownBy(() -> repository.registrar(transicao))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("transição exige transação pendente e causa de criação local");

        assertThat(repository.buscarPorEvento(transicao.eventId())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM transacoes WHERE id = ?",
                String.class, transicao.transactionId())).isEqualTo("PENDENTE");
    }

    @Test
    void registrar_deveTruncarSomenteInstanteLocalParaMicros() {
        var original = preparar(StatusTransacao.APROVADA);
        var entrada = new TransicaoRecebida(original.eventId(), original.transactionId(), original.eventType(),
                original.eventVersion(), original.correlationId(), original.causationId(), original.estadoAnterior(),
                original.estadoFinal(), original.origem(), original.occurredAt(),
                Instant.parse("2026-10-03T12:00:01.654321987Z"));

        repository.registrar(entrada);

        assertThat(repository.buscarPorEvento(original.eventId())).contains(original);
    }

    private TransicaoRecebida preparar(StatusTransacao resultado) {
        var id = UUID.randomUUID();
        var causa = UUID.randomUUID();
        jdbc.update("INSERT INTO transacoes (id, valor, moeda, status) VALUES (?, 123.450, 'BRL', 'PENDENTE')", id);
        jdbc.update("""
                INSERT INTO outbox_eventos (event_id, aggregate_id, event_type, event_version, payload, occurred_at)
                VALUES (?, ?, 'TransacaoCriada', 1, '{}'::jsonb, NOW())
                """, causa, id);
        return new TransicaoRecebida(UUID.randomUUID(), id, "TransacaoProcessada", 1, id, causa,
                StatusTransacao.PENDENTE, resultado, "processamento-service/TransacaoProcessada.v1",
                Instant.parse("2026-10-03T12:00:00.123456789Z"),
                Instant.parse("2026-10-03T12:00:01.654321Z").truncatedTo(ChronoUnit.MICROS));
    }
}
