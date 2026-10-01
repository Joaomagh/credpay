package br.com.credpay.processamento.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import br.com.credpay.processamento.application.EventoSaidaPendente;
import br.com.credpay.processamento.application.OutboxProcessamentoRepository;
import br.com.credpay.processamento.application.ProcessamentoRegistrado;
import br.com.credpay.processamento.application.ProcessamentoRepository;
import br.com.credpay.processamento.domain.StatusProcessamento;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@DataJpaTest(properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "credpay.processamento.limites.BRL=100.00"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ProcessamentoJpaRepository.class, OutboxProcessamentoJdbcRepository.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class OutboxProcessamentoJdbcRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            "postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0")
            .withDatabaseName("credpay_processamento_outbox_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configurarBanco(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private ProcessamentoRepository processamentos;
    @Autowired private OutboxProcessamentoRepository outbox;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void limparDados() {
        jdbc.update("delete from outbox_eventos");
        jdbc.update("delete from processamentos");
    }

    @Test
    void adicionar_devePermitirRoundTripEmOutraTransacao_quandoResultadoForConfirmado() throws Exception {
        var entradaId = UUID.randomUUID();
        var transacaoId = UUID.randomUUID();
        var saidaId = UUID.randomUUID();
        var processadoEm = Instant.parse("2026-10-01T12:00:01.123456Z");
        var resultado = new ProcessamentoRegistrado(entradaId, transacaoId,
                "TransacaoCriada", 1, Instant.parse("2026-10-01T12:00:00.123456789Z"),
                transacaoId, "PENDENTE", new BigDecimal("75.00"), Currency.getInstance("BRL"),
                new BigDecimal("100.00"), StatusProcessamento.APROVADA, processadoEm, saidaId);
        var payload = """
                {"eventId":"%s","eventType":"TransacaoProcessada","eventVersion":1,
                 "occurredAt":"2026-10-01T12:00:01.123456Z","correlationId":"%s",
                 "causationId":"%s","data":{"transactionId":"%s","status":"APROVADA"}}
                """.formatted(saidaId, transacaoId, entradaId, transacaoId);
        var evento = new EventoSaidaPendente(saidaId, transacaoId,
                "TransacaoProcessada", 1, payload, processadoEm);
        var transacoes = new TransactionTemplate(transactionManager);

        transacoes.executeWithoutResult(status -> {
            processamentos.inserir(resultado);
            outbox.adicionar(evento);
        });
        var encontrado = transacoes.execute(status -> outbox.buscarPorEventId(saidaId));

        assertThat(encontrado).isPresent();
        var persistido = encontrado.orElseThrow();
        assertThat(persistido.eventId()).isEqualTo(saidaId);
        assertThat(persistido.aggregateId()).isEqualTo(transacaoId);
        assertThat(persistido.eventType()).isEqualTo("TransacaoProcessada");
        assertThat(persistido.eventVersion()).isEqualTo(1);
        assertThat(persistido.occurredAt()).isEqualTo(processadoEm);
        assertThat(new ObjectMapper().readTree(persistido.payload()))
                .isEqualTo(new ObjectMapper().readTree(payload));
    }

    @Test
    void adicionar_devePreservarOriginal_quandoEventIdDaSaidaForDuplicado() throws Exception {
        var entradaId = UUID.randomUUID();
        var transacaoId = UUID.randomUUID();
        var saidaId = UUID.randomUUID();
        var processadoEm = Instant.parse("2026-10-01T12:00:01.123456Z");
        var resultado = new ProcessamentoRegistrado(entradaId, transacaoId,
                "TransacaoCriada", 1, Instant.parse("2026-10-01T12:00:00Z"),
                transacaoId, "PENDENTE", new BigDecimal("75.00"), Currency.getInstance("BRL"),
                new BigDecimal("100.00"), StatusProcessamento.APROVADA, processadoEm, saidaId);
        var original = new EventoSaidaPendente(saidaId, transacaoId,
                "TransacaoProcessada", 1, "{\"original\":true}", processadoEm);
        var conflitante = new EventoSaidaPendente(saidaId, UUID.randomUUID(),
                "TransacaoProcessada", 1, "{\"original\":false}", processadoEm);
        var transacoes = new TransactionTemplate(transactionManager);
        transacoes.executeWithoutResult(status -> {
            processamentos.inserir(resultado);
            outbox.adicionar(original);
        });

        assertThatThrownBy(() -> transacoes.executeWithoutResult(status -> outbox.adicionar(conflitante)))
                .isInstanceOf(DataIntegrityViolationException.class);
        var persistido = transacoes.execute(status -> outbox.buscarPorEventId(saidaId));
        assertThat(persistido).isPresent();
        assertThat(new ObjectMapper().readTree(persistido.orElseThrow().payload()))
                .isEqualTo(new ObjectMapper().readTree(original.payload()));
        assertThat(persistido.orElseThrow().aggregateId()).isEqualTo(transacaoId);
    }

    @Test
    void buscarPendentes_deveOrdenarPorInstanteEId_semIncluirPublicados() {
        var primeiro = persistir(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                Instant.parse("2026-10-01T12:00:00Z"));
        var segundo = persistir(UUID.fromString("00000000-0000-0000-0000-000000000002"),
                Instant.parse("2026-10-01T12:00:01Z"));
        var terceiro = persistir(UUID.fromString("00000000-0000-0000-0000-000000000003"),
                Instant.parse("2026-10-01T12:00:01Z"));
        persistir(UUID.fromString("00000000-0000-0000-0000-000000000004"),
                Instant.parse("2026-10-01T12:00:02Z"));
        outbox.marcarPublicado(primeiro.eventId(), Instant.parse("2026-10-01T12:01:00Z"));

        var pendentes = outbox.buscarPendentes(2);

        assertThat(pendentes).extracting(EventoSaidaPendente::eventId)
                .containsExactly(segundo.eventId(), terceiro.eventId());
    }

    @Test
    void marcarPublicado_devePreservarPrimeiroInstante_quandoRepetido() {
        var evento = persistir(UUID.randomUUID(), Instant.parse("2026-10-01T12:00:03Z"));
        var primeiroInstante = Instant.parse("2026-10-01T12:01:00Z");
        outbox.marcarPublicado(evento.eventId(), primeiroInstante);
        outbox.marcarPublicado(evento.eventId(), primeiroInstante.plusSeconds(10));
        outbox.marcarPublicado(UUID.randomUUID(), primeiroInstante);

        var publicadoEm = jdbc.queryForObject(
                "select published_at from outbox_eventos where event_id = ?",
                Timestamp.class, evento.eventId());

        assertThat(publicadoEm).isNotNull();
        assertThat(publicadoEm.toInstant()).isEqualTo(primeiroInstante);
        assertThat(outbox.buscarPendentes(10)).noneMatch(pendente ->
                pendente.eventId().equals(evento.eventId()));
    }

    private EventoSaidaPendente persistir(UUID saidaId, Instant processadoEm) {
        var entradaId = UUID.randomUUID();
        var transacaoId = UUID.randomUUID();
        var resultado = new ProcessamentoRegistrado(entradaId, transacaoId,
                "TransacaoCriada", 1, Instant.parse("2026-10-01T11:59:59Z"),
                transacaoId, "PENDENTE", new BigDecimal("75.00"), Currency.getInstance("BRL"),
                new BigDecimal("100.00"), StatusProcessamento.APROVADA, processadoEm, saidaId);
        var evento = new EventoSaidaPendente(saidaId, transacaoId,
                "TransacaoProcessada", 1, "{\"eventId\":\"%s\"}".formatted(saidaId), processadoEm);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            processamentos.inserir(resultado);
            outbox.adicionar(evento);
        });
        return evento;
    }
}
