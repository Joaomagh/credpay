package br.com.credpay.processamento.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "credpay.processamento.limites.BRL=100.00")
@Import(RegistrarProcessamentoOutboxIntegrationTest.FalhaDaOutboxConfiguration.class)
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
class RegistrarProcessamentoOutboxIntegrationTest {

    private static final List<HikariDataSource> POOLS_ORIGINAIS = new ArrayList<>();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgresDaFixture()
            .withDatabaseName("credpay_processamento_atomic_test")
            .withUsername("test")
            .withPassword("test");

    private static final class PostgresDaFixture extends PostgreSQLContainer<PostgresDaFixture> {
        private PostgresDaFixture() {
            super("postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0");
        }

        @Override
        public void stop() {
            try {
                var pools = List.copyOf(POOLS_ORIGINAIS);
                assertThat(pools).withFailMessage("fixture registro/outbox não capturou seus pools originais").isNotEmpty();
                assertThat(isRunning()).withFailMessage("PostgreSQL parou antes da observação do registro/outbox").isTrue();
                pools.forEach(pool -> System.out.println("CredPay fixture lifecycle RegistroOutbox: pool="
                        + pool.getPoolName() + " closed=" + pool.isClosed() + " postgresRunning=true"));
                assertThat(pools.stream().allMatch(HikariDataSource::isClosed))
                        .withFailMessage("todos os pools do registro/outbox devem fechar antes do PostgreSQL").isTrue();
            } finally {
                super.stop();
            }
        }
    }

    @DynamicPropertySource
    static void configurarBanco(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private RegistrarProcessamentoService service;
    @Autowired private ProcessamentoRepository processamentos;
    @Autowired private OutboxProcessamentoRepository outbox;
    @Autowired private FalhaControlada falha;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private HikariDataSource dataSource;

    @BeforeEach
    void limparFalha() {
        POOLS_ORIGINAIS.add(dataSource);
        falha.desarmar();
    }

    @Test
    void executar_devePersistirUmaIntencaoEReutilizaLa_quandoEntradaForReentregue() throws Exception {
        var entrada = entrada();

        var primeiro = service.executar(entrada);
        var replay = service.executar(entrada);

        assertThat(replay).isEqualTo(primeiro);
        var evento = outbox.buscarPorEventId(primeiro.outputEventId()).orElseThrow();
        assertThat(evento.aggregateId()).isEqualTo(entrada.transactionId());
        assertThat(evento.eventType()).isEqualTo("TransacaoProcessada");
        assertThat(evento.eventVersion()).isEqualTo(1);
        assertThat(evento.occurredAt()).isEqualTo(primeiro.processedAt());
        var payload = new ObjectMapper().readTree(evento.payload());
        assertThat(payload.path("eventId").asText()).isEqualTo(primeiro.outputEventId().toString());
        assertThat(payload.path("eventType").asText()).isEqualTo("TransacaoProcessada");
        assertThat(payload.path("eventVersion").asInt()).isEqualTo(1);
        assertThat(Instant.parse(payload.path("occurredAt").asText())).isEqualTo(primeiro.processedAt());
        assertThat(payload.path("correlationId").asText()).isEqualTo(entrada.transactionId().toString());
        assertThat(payload.path("causationId").asText()).isEqualTo(entrada.eventId().toString());
        assertThat(payload.path("data").path("transactionId").asText())
                .isEqualTo(entrada.transactionId().toString());
        assertThat(payload.path("data").path("status").asText()).isEqualTo("APROVADA");
        assertThat(payload.path("data").size()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from outbox_eventos where aggregate_id = ?",
                Integer.class, entrada.transactionId())).isEqualTo(1);
    }

    @Test
    void executar_deveReverterResultadoEIntencao_quandoOutboxFalharDepoisDaEscrita() {
        var entrada = entrada();
        falha.armar();

        assertThatThrownBy(() -> service.executar(entrada))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("falha apos insercao da outbox");

        assertThat(processamentos.buscarPorEventId(entrada.eventId())).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from outbox_eventos where aggregate_id = ?",
                Integer.class, entrada.transactionId())).isZero();
    }

    private TransacaoCriadaRecebida entrada() {
        var transactionId = UUID.randomUUID();
        return new TransacaoCriadaRecebida(UUID.randomUUID(), transactionId,
                Instant.parse("2026-10-01T12:00:00.123456789Z"), transactionId,
                new BigDecimal("75.00"), Currency.getInstance("BRL"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FalhaDaOutboxConfiguration {
        @Bean @Primary
        FalhaControlada outboxComFalhaControlada(
                @Qualifier("outboxProcessamentoJdbcRepository") OutboxProcessamentoRepository delegate) {
            return new FalhaControlada(delegate);
        }
    }

    static class FalhaControlada implements OutboxProcessamentoRepository {
        private final OutboxProcessamentoRepository delegate;
        private final AtomicBoolean falhar = new AtomicBoolean();

        FalhaControlada(OutboxProcessamentoRepository delegate) {
            this.delegate = delegate;
        }

        void armar() { falhar.set(true); }
        void desarmar() { falhar.set(false); }

        @Override public void adicionar(EventoSaidaPendente evento) {
            delegate.adicionar(evento);
            if (falhar.getAndSet(false)) {
                throw new IllegalStateException("falha apos insercao da outbox");
            }
        }

        @Override public java.util.Optional<EventoSaidaPendente> buscarPorEventId(UUID eventId) {
            return delegate.buscarPorEventId(eventId);
        }

        @Override public java.util.List<EventoSaidaPendente> buscarPendentes(int limite) {
            return delegate.buscarPendentes(limite);
        }

        @Override public void marcarPublicado(UUID eventId, Instant publicadoEm) {
            delegate.marcarPublicado(eventId, publicadoEm);
        }
    }
}
