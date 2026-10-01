package br.com.credpay.processamento.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

import br.com.credpay.processamento.application.ProcessamentoRegistrado;
import br.com.credpay.processamento.application.ProcessamentoRepository;
import br.com.credpay.processamento.domain.StatusProcessamento;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
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
        "credpay.processamento.limites.BRL=100.00",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
        "spring.jpa.properties.hibernate.cache.use_query_cache=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ProcessamentoJpaRepository.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class ProcessamentoRepositoryIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            "postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0")
            .withDatabaseName("credpay_processamento_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configurarBanco(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private ProcessamentoRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    void inserir_devePermitirLeituraCompletaEmOutraTransacao_quandoCommitForConcluido() {
        var processamento = new ProcessamentoRegistrado(
                UUID.fromString("61e71d1c-2844-459d-8490-c77765b23690"),
                UUID.fromString("a76a5537-a838-4ae5-a52d-26cd8463b7f0"),
                "TransacaoCriada",
                1,
                Instant.parse("2026-09-30T12:00:00.123456789Z"),
                UUID.fromString("a76a5537-a838-4ae5-a52d-26cd8463b7f0"),
                "PENDENTE",
                new BigDecimal("123.450"),
                Currency.getInstance("BRL"),
                new BigDecimal("200.0000"),
                StatusProcessamento.APROVADA,
                Instant.parse("2026-09-30T12:00:01.987654Z"),
                UUID.fromString("ed826c39-d7cc-4fe2-a225-b106e8b73367"));
        var transacoes = new TransactionTemplate(transactionManager);

        transacoes.executeWithoutResult(status -> repository.inserir(processamento));
        Optional<ProcessamentoRegistrado> encontrado = transacoes.execute(
                status -> repository.buscarPorEventId(processamento.eventId()));

        assertThat(encontrado).isPresent();
        var persistido = encontrado.orElseThrow();
        assertThat(persistido).isNotSameAs(processamento);
        assertThat(persistido.eventId()).isEqualTo(processamento.eventId());
        assertThat(persistido.transactionId()).isEqualTo(processamento.transactionId());
        assertThat(persistido.eventType()).isEqualTo("TransacaoCriada");
        assertThat(persistido.eventVersion()).isEqualTo(1);
        assertThat(persistido.occurredAt()).isEqualTo(processamento.occurredAt());
        assertThat(persistido.correlationId()).isEqualTo(processamento.correlationId());
        assertThat(persistido.inputStatus()).isEqualTo("PENDENTE");
        assertThat(persistido.valor()).isEqualByComparingTo(processamento.valor());
        assertThat(persistido.valor().scale()).isEqualTo(3);
        assertThat(persistido.moeda()).isEqualTo(Currency.getInstance("BRL"));
        assertThat(persistido.limiteAplicado()).isEqualByComparingTo(processamento.limiteAplicado());
        assertThat(persistido.limiteAplicado().scale()).isEqualTo(4);
        assertThat(persistido.status()).isEqualTo(StatusProcessamento.APROVADA);
        assertThat(persistido.processedAt()).isEqualTo(processamento.processedAt());
        assertThat(persistido.outputEventId()).isEqualTo(processamento.outputEventId());
    }

    @Test
    void inserir_deveFalharSemSobrescreverOriginal_quandoEventIdColidir() {
        var original = processamentoValido(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var conflitante = processamentoDivergente(
                original.eventId(), UUID.randomUUID(), UUID.randomUUID());

        comprovarColisaoSemSobrescrita(
                original, conflitante, "processamentos_pkey");
    }

    @Test
    void inserir_deveFalharSemSobrescreverOriginal_quandoTransactionIdColidir() {
        var original = processamentoValido(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var conflitante = processamentoDivergente(
                UUID.randomUUID(), original.transactionId(), UUID.randomUUID());

        comprovarColisaoSemSobrescrita(
                original, conflitante, "uq_processamentos_transaction_id");
    }

    @Test
    void inserir_deveFalharSemSobrescreverOriginal_quandoOutputEventIdColidir() {
        var original = processamentoValido(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var conflitante = processamentoDivergente(
                UUID.randomUUID(), UUID.randomUUID(), original.outputEventId());

        comprovarColisaoSemSobrescrita(
                original, conflitante, "uq_processamentos_output_event_id");
    }

    @Test
    void inserir_deveDescartarRegistro_quandoTransacaoForRevertidaAposFlush() {
        var processamento = processamentoValido(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        var transacoes = new TransactionTemplate(transactionManager);

        transacoes.executeWithoutResult(status -> {
            repository.inserir(processamento);
            entityManager.flush();
            status.setRollbackOnly();
        });

        var encontrado = transacoes.execute(
                status -> repository.buscarPorEventId(processamento.eventId()));
        assertThat(encontrado).isEmpty();
    }

    private void comprovarColisaoSemSobrescrita(
            ProcessamentoRegistrado original,
            ProcessamentoRegistrado conflitante,
            String constraintEsperada) {
        var transacoes = new TransactionTemplate(transactionManager);
        transacoes.executeWithoutResult(status -> repository.inserir(original));

        assertThatThrownBy(() -> transacoes.executeWithoutResult(
                status -> repository.inserir(conflitante)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasStackTraceContaining(constraintEsperada);

        var encontrado = transacoes.execute(
                status -> repository.buscarPorEventId(original.eventId()));
        assertThat(encontrado).contains(original);
    }

    private ProcessamentoRegistrado processamentoValido(
            UUID eventId, UUID transactionId, UUID outputEventId) {
        return new ProcessamentoRegistrado(
                eventId,
                transactionId,
                "TransacaoCriada",
                1,
                Instant.parse("2026-09-30T12:00:00.123456789Z"),
                transactionId,
                "PENDENTE",
                new BigDecimal("10.00"),
                Currency.getInstance("BRL"),
                new BigDecimal("100.000"),
                StatusProcessamento.APROVADA,
                Instant.parse("2026-09-30T12:00:01.123456Z"),
                outputEventId);
    }

    private ProcessamentoRegistrado processamentoDivergente(
            UUID eventId, UUID transactionId, UUID outputEventId) {
        return new ProcessamentoRegistrado(
                eventId,
                transactionId,
                "TransacaoCriada",
                1,
                Instant.parse("2026-09-30T13:00:00.987654321Z"),
                transactionId,
                "PENDENTE",
                new BigDecimal("200.000"),
                Currency.getInstance("USD"),
                new BigDecimal("50.00"),
                StatusProcessamento.REJEITADA,
                Instant.parse("2026-09-30T13:00:01.654321Z"),
                outputEventId);
    }
}
