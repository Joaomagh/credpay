package br.com.credpay.transacoes.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import br.com.credpay.transacoes.domain.StatusTransacao;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Import(AplicarResultadoConcorrenciaIntegrationTest.RelogioDeTeste.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class AplicarResultadoConcorrenciaIntegrationTest {

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

    @Autowired private AplicarResultadoService service;
    @Autowired private CriarTransacao criar;
    @Autowired private BuscarTransacao buscar;
    @Autowired private TransicaoRepository historico;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private RelogioControlado clock;

    @Test
    void executar_deveConvergirAposEsperaReal_quandoEntregasEquivalentesConcorrerem() throws Exception {
        var entrada = preparar();

        var disputa = disputar(entrada, entrada, false);

        assertThat(disputa.primeira().erro()).isNull();
        assertThat(disputa.segunda().erro()).isNull();
        assertThat(disputa.segunda().valor()).isEqualTo(disputa.primeira().valor());
        assertRegistroUnico(disputa.primeira().valor());
        assertThat(clock.chamadas.get()).isEqualTo(1);
    }

    private Disputa disputar(TransacaoProcessadaRecebida primeiraEntrada,
            TransacaoProcessadaRecebida segundaEntrada, boolean falharPrimeira) throws Exception {
        var porta = clock.armar(falharPrimeira);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var primeira = executor.submit(() -> service.executar(primeiraEntrada));
            assertThat(porta.entrou().await(10, TimeUnit.SECONDS)).isTrue();
            var segundaIniciada = new CountDownLatch(1);
            var segundoPid = new AtomicInteger();
            var segunda = executor.submit(() -> {
                var transacao = new TransactionTemplate(transactionManager);
                transacao.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
                transacao.setTimeout(15);
                return transacao.execute(status -> {
                    segundoPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    segundaIniciada.countDown();
                    return service.executar(segundaEntrada);
                });
            });
            try {
                assertThat(segundaIniciada.await(10, TimeUnit.SECONDS)).isTrue();
                aguardarLockDaConexao(segundoPid.get());
            } finally {
                porta.liberar().countDown();
            }
            return new Disputa(resultadoDe(primeira), resultadoDe(segunda));
        } finally {
            porta.liberar().countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    private ResultadoChamada resultadoDe(Future<TransicaoRecebida> futuro) throws Exception {
        try {
            return new ResultadoChamada(futuro.get(10, TimeUnit.SECONDS), null);
        } catch (ExecutionException exception) {
            return new ResultadoChamada(null, exception.getCause());
        }
    }

    private void aguardarLockDaConexao(int pid) {
        var fim = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < fim) {
            var bloqueada = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM pg_locks
                    WHERE pid = ? AND locktype = 'advisory' AND NOT granted
                    AND database = (SELECT oid FROM pg_database WHERE datname = current_database()))
                    """, Boolean.class, pid);
            if (Boolean.TRUE.equals(bloqueada)) {
                return;
            }
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
        throw new AssertionError("segunda conexão não aguardou advisory lock");
    }

    private TransacaoProcessadaRecebida preparar() {
        var transacao = criar.executar(UUID.randomUUID(), new BigDecimal("123.450"), Currency.getInstance("BRL"));
        var causa = jdbc.queryForObject("SELECT event_id FROM outbox_eventos WHERE aggregate_id = ?",
                UUID.class, transacao.id());
        return new TransacaoProcessadaRecebida(UUID.randomUUID(), transacao.id(),
                Instant.parse("2026-10-03T12:00:00.123456789Z"), transacao.id(), causa, StatusTransacao.APROVADA);
    }

    private void assertRegistroUnico(TransicaoRecebida esperada) {
        assertThat(historico.buscarPorEvento(esperada.eventId())).contains(esperada);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM historico_transacoes WHERE transaction_id = ?",
                Integer.class, esperada.transactionId())).isEqualTo(1);
        var transacao = buscar.executar(esperada.transactionId());
        assertThat(transacao.status()).isEqualTo(esperada.estadoFinal());
        assertThat(transacao.valor()).isEqualTo(new BigDecimal("123.450"));
        assertThat(transacao.moeda()).isEqualTo(Currency.getInstance("BRL"));
    }

    private record ResultadoChamada(TransicaoRecebida valor, Throwable erro) { }
    private record Disputa(ResultadoChamada primeira, ResultadoChamada segunda) { }
    private record Porta(CountDownLatch entrou, CountDownLatch liberar, boolean falhar) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class RelogioDeTeste {
        @Bean @Primary RelogioControlado relogioControlado() { return new RelogioControlado(); }
    }

    static class RelogioControlado extends Clock {
        private final AtomicInteger chamadas = new AtomicInteger();
        private final AtomicReference<Porta> proxima = new AtomicReference<>();

        Porta armar(boolean falhar) {
            chamadas.set(0);
            var porta = new Porta(new CountDownLatch(1), new CountDownLatch(1), falhar);
            proxima.set(porta);
            return porta;
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() {
            chamadas.incrementAndGet();
            var porta = proxima.getAndSet(null);
            if (porta != null) {
                porta.entrou().countDown();
                try {
                    if (!porta.liberar().await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("tempo esgotado aguardando teste");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("teste interrompido", exception);
                }
                if (porta.falhar()) {
                    throw new IllegalStateException("falha técnica simulada antes do SQL");
                }
            }
            return Instant.parse("2026-10-03T12:00:01.987654Z");
        }
    }
}
