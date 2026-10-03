package br.com.credpay.transacoes.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import br.com.credpay.transacoes.domain.StatusTransacao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AplicarResultadoIntegrationTest {

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
    private AplicarResultadoService service;

    @Autowired
    private CriarTransacao criar;

    @Autowired
    private BuscarTransacao buscar;

    @Autowired
    private TransicaoRepository historico;

    @Autowired
    private JdbcTemplate jdbc;

    @ParameterizedTest
    @EnumSource(value = StatusTransacao.class, names = {"APROVADA", "REJEITADA"})
    void executar_deveAplicarERepetirTransicaoSemExigirMarcacaoDaCriacao(StatusTransacao estado) {
        var entrada = preparar(estado);

        var primeira = service.executar(entrada);
        var equivalente = new TransacaoProcessadaRecebida(entrada.eventId(), entrada.transactionId(),
                Instant.parse("2026-10-03T15:00:00.123456789+03:00"), entrada.correlationId(),
                entrada.causationId(), entrada.status());
        var replay = service.executar(equivalente);

        assertThat(replay).isEqualTo(primeira);
        assertThat(historico.buscarPorEvento(entrada.eventId())).contains(primeira);
        assertThat(primeira.occurredAt()).isEqualTo(entrada.occurredAt());
        assertThat(primeira.aplicadoEm().getNano() % 1000).isZero();
        assertEstadoEDados(entrada.transactionId(), estado);
        assertThat(contarHistorico(entrada.transactionId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_eventos WHERE aggregate_id = ?",
                Integer.class, entrada.transactionId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT published_at IS NULL FROM outbox_eventos WHERE event_id = ?",
                Boolean.class, entrada.causationId())).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"transacao", "causa", "instante", "status"})
    void executar_devePreservarRegistroCompleto_quandoMesmoEventoDivergir(String campo) {
        var original = preparar(StatusTransacao.APROVADA);
        var registrada = service.executar(original);
        var outra = preparar(StatusTransacao.REJEITADA);
        var divergente = new TransacaoProcessadaRecebida(original.eventId(),
                campo.equals("transacao") ? outra.transactionId() : original.transactionId(),
                campo.equals("instante") ? original.occurredAt().plusNanos(1) : original.occurredAt(),
                campo.equals("transacao") ? outra.correlationId() : original.correlationId(),
                campo.equals("causa") ? outra.causationId() : original.causationId(),
                campo.equals("status") ? StatusTransacao.REJEITADA : original.status());

        assertThatThrownBy(() -> service.executar(divergente)).isInstanceOf(ConflitoResultadoException.class);

        assertThat(historico.buscarPorEvento(original.eventId())).contains(registrada);
        assertEstadoEDados(original.transactionId(), StatusTransacao.APROVADA);
        assertEstadoEDados(outra.transactionId(), StatusTransacao.PENDENTE);
        assertThat(contarHistorico(original.transactionId())).isEqualTo(1);
        assertThat(contarHistorico(outra.transactionId())).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = StatusTransacao.class, names = {"APROVADA", "REJEITADA"})
    void executar_deveRecusarOutroEventoMesmoComStatusIgual(StatusTransacao estado) {
        var original = preparar(estado);
        var registrada = service.executar(original);
        var novo = new TransacaoProcessadaRecebida(UUID.randomUUID(), original.transactionId(),
                original.occurredAt(), original.correlationId(), original.causationId(), estado);

        assertThatThrownBy(() -> service.executar(novo)).isInstanceOf(ConflitoResultadoException.class);

        assertThat(historico.buscarPorEvento(original.eventId())).contains(registrada);
        assertThat(historico.buscarPorEvento(novo.eventId())).isEmpty();
        assertEstadoEDados(original.transactionId(), estado);
        assertThat(contarHistorico(original.transactionId())).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ausente", "outra_transacao", "outro_tipo", "outra_versao"})
    void executar_deveRecusarCausaInvalidaSemAlterarTransacao(String caso) {
        var original = preparar(StatusTransacao.APROVADA);
        var causa = original.causationId();
        if (caso.equals("ausente")) {
            causa = UUID.randomUUID();
        } else if (caso.equals("outra_transacao")) {
            causa = preparar(StatusTransacao.REJEITADA).causationId();
        } else {
            jdbc.update("UPDATE outbox_eventos SET event_type = ?, event_version = ? WHERE event_id = ?",
                    caso.equals("outro_tipo") ? "Outro" : "TransacaoCriada",
                    caso.equals("outra_versao") ? 2 : 1, causa);
        }
        var invalida = new TransacaoProcessadaRecebida(original.eventId(), original.transactionId(),
                original.occurredAt(), original.correlationId(), causa, original.status());

        assertThatThrownBy(() -> service.executar(invalida)).isInstanceOf(TransicaoRecusadaException.class);

        assertEstadoEDados(original.transactionId(), StatusTransacao.PENDENTE);
        assertThat(contarHistorico(original.transactionId())).isZero();
    }

    @Test
    void executar_deveRecusarTransacaoDesconhecidaSemInventarDados() {
        var id = UUID.randomUUID();
        var entrada = new TransacaoProcessadaRecebida(UUID.randomUUID(), id, Instant.EPOCH,
                id, UUID.randomUUID(), StatusTransacao.APROVADA);

        assertThatThrownBy(() -> service.executar(entrada)).isInstanceOf(TransicaoRecusadaException.class);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transacoes WHERE id = ?", Integer.class, id)).isZero();
        assertThat(contarHistorico(id)).isZero();
    }

    private TransacaoProcessadaRecebida preparar(StatusTransacao estado) {
        var transacao = criar.executar(UUID.randomUUID(), new BigDecimal("123.450"), Currency.getInstance("BRL"));
        var causa = jdbc.queryForObject("SELECT event_id FROM outbox_eventos WHERE aggregate_id = ?",
                UUID.class, transacao.id());
        return new TransacaoProcessadaRecebida(UUID.randomUUID(), transacao.id(),
                Instant.parse("2026-10-03T12:00:00.123456789Z"), transacao.id(), causa, estado);
    }

    private void assertEstadoEDados(UUID id, StatusTransacao estado) {
        var transacao = buscar.executar(id);
        assertThat(transacao.id()).isEqualTo(id);
        assertThat(transacao.status()).isEqualTo(estado);
        assertThat(transacao.valor()).isEqualTo(new BigDecimal("123.450"));
        assertThat(transacao.moeda()).isEqualTo(Currency.getInstance("BRL"));
    }

    private int contarHistorico(UUID id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM historico_transacoes WHERE transaction_id = ?", Integer.class, id);
    }
}
