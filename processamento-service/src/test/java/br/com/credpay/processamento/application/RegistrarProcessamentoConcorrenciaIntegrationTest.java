package br.com.credpay.processamento.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "credpay.processamento.limites.BRL=100.00")
@Import(RegistrarProcessamentoConcorrenciaIntegrationTest.ColaboradoresDeTeste.class)
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RegistrarProcessamentoConcorrenciaIntegrationTest {

    private static final List<HikariDataSource> POOLS_ORIGINAIS = new ArrayList<>();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgresDaFixture()
            .withDatabaseName("credpay_processamento_concorrencia")
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
                assertThat(pools).withFailMessage("fixture concorrência do processador não capturou seus pools originais").isNotEmpty();
                assertThat(isRunning()).withFailMessage("PostgreSQL já estava parado antes da observação da concorrência do processador").isTrue();
                pools.forEach(pool -> System.out.println("CredPay fixture lifecycle ProcessamentoConcorrencia: pool="
                        + pool.getPoolName() + " closed=" + pool.isClosed() + " postgresRunning=true"));
                assertThat(pools.stream().allMatch(HikariDataSource::isClosed))
                        .withFailMessage("todos os pools da concorrência do processador devem fechar antes do PostgreSQL").isTrue();
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
    @Autowired private ProcessamentoRepository repository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private HikariDataSource dataSource;
    @Autowired private PoliticaControlada politica;
    @Autowired private RelogioContado relogio;
    @Autowired private GeradorContado gerador;

    @BeforeEach
    void limparContadores() {
        POOLS_ORIGINAIS.add(dataSource);
        politica.resetar();
        relogio.chamadas.set(0);
        gerador.chamadas.set(0);
    }

    @Test
    void executar_deveConvergirParaUmResultado_quandoEntradasEquivalentesConcorrerem() throws Exception {
        var entrada = entrada(UUID.randomUUID(), UUID.randomUUID(), "75.0");
        var equivalente = entrada(entrada.eventId(), entrada.transactionId(), "75.00");
        var disputa = executarDisputa(entrada, equivalente, false);

        assertThat(disputa.segunda()).isEqualTo(disputa.primeira());
        comprovarRegistroUnico(disputa.primeira());
        comprovarUmaDecisao();
    }

    @ParameterizedTest
    @EnumSource(Divergencia.class)
    void executar_devePreservarVencedor_quandoEntradasDivergentesConcorrerem(Divergencia divergencia)
            throws Exception {
        var entrada = entrada(UUID.randomUUID(), UUID.randomUUID(), "75.00");
        var outra = switch (divergencia) {
            case VALOR -> entrada(entrada.eventId(), entrada.transactionId(), "76.00");
            case TRANSACAO -> entrada(entrada.eventId(), UUID.randomUUID(), "75.00");
            case EVENTO -> entrada(UUID.randomUUID(), entrada.transactionId(), "75.00");
        };
        var disputa = executarDisputa(entrada, outra, false);

        assertThat(disputa.erro()).isInstanceOf(ConflitoProcessamentoException.class);
        comprovarRegistroUnico(disputa.primeira());
        comprovarUmaDecisao();
    }

    @Test
    void executar_devePermitirNovaTentativa_quandoPrimeiraTransacaoFalhar() throws Exception {
        var entrada = entrada(UUID.randomUUID(), UUID.randomUUID(), "75.00");
        var disputa = executarDisputa(entrada, entrada, true);

        assertThat(disputa.erro()).isInstanceOf(IllegalStateException.class)
                .hasMessage("falha tecnica simulada");
        comprovarRegistroUnico(disputa.segunda());
        assertThat(politica.chamadas.get()).isEqualTo(2);
        assertThat(relogio.chamadas.get()).isEqualTo(1);
        assertThat(gerador.chamadas.get()).isEqualTo(1);
    }

    private Disputa executarDisputa(
            TransacaoCriadaRecebida primeiraEntrada,
            TransacaoCriadaRecebida segundaEntrada,
            boolean falharPrimeira) throws Exception {
        var porta = politica.armar(falharPrimeira);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var primeira = executor.submit(() -> service.executar(primeiraEntrada));
            assertThat(porta.entrou().await(10, TimeUnit.SECONDS)).isTrue();
            var segunda = executor.submit(() -> service.executar(segundaEntrada));
            try {
                aguardarLockEmEspera();
            } finally {
                porta.liberar().countDown();
            }

            if (falharPrimeira) {
                var erro = falhaDe(primeira);
                return new Disputa(null, segunda.get(10, TimeUnit.SECONDS), erro);
            }
            var confirmado = primeira.get(10, TimeUnit.SECONDS);
            if (!primeiraEntrada.equals(segundaEntrada)
                    && !(primeiraEntrada.eventId().equals(segundaEntrada.eventId())
                    && primeiraEntrada.transactionId().equals(segundaEntrada.transactionId())
                    && primeiraEntrada.valor().compareTo(segundaEntrada.valor()) == 0)) {
                return new Disputa(confirmado, null, falhaDe(segunda));
            }
            return new Disputa(confirmado, segunda.get(10, TimeUnit.SECONDS), null);
        } finally {
            porta.liberar().countDown();
        }
    }

    private Throwable falhaDe(java.util.concurrent.Future<ProcessamentoRegistrado> futuro) {
        try {
            futuro.get(10, TimeUnit.SECONDS);
            throw new AssertionError("a chamada deveria falhar");
        } catch (ExecutionException exception) {
            return exception.getCause();
        } catch (Exception exception) {
            throw new AssertionError("falha ao aguardar chamada concorrente", exception);
        }
    }

    private void aguardarLockEmEspera() {
        var fim = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < fim) {
            Integer aguardando = jdbc.queryForObject("""
                    select count(*) from pg_locks
                    where locktype = 'advisory' and not granted
                      and database = (select oid from pg_database where datname = current_database())
                    """, Integer.class);
            if (aguardando != null && aguardando > 0) {
                return;
            }
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
        throw new AssertionError("segunda transacao nao aguardou o advisory lock");
    }

    private void comprovarRegistroUnico(ProcessamentoRegistrado esperado) {
        assertThat(repository.buscarPorEventId(esperado.eventId())).contains(esperado);
        assertThat(jdbc.queryForObject("select count(*) from processamentos where transaction_id = ?",
                Integer.class, esperado.transactionId())).isEqualTo(1);
    }

    private void comprovarUmaDecisao() {
        assertThat(politica.chamadas.get()).isEqualTo(1);
        assertThat(relogio.chamadas.get()).isEqualTo(1);
        assertThat(gerador.chamadas.get()).isEqualTo(1);
    }

    private TransacaoCriadaRecebida entrada(UUID eventId, UUID transactionId, String valor) {
        return new TransacaoCriadaRecebida(eventId, transactionId,
                Instant.parse("2026-09-30T12:00:00.123456789Z"), transactionId,
                new BigDecimal(valor), Currency.getInstance("BRL"));
    }

    private record Disputa(ProcessamentoRegistrado primeira, ProcessamentoRegistrado segunda, Throwable erro) {
    }

    private enum Divergencia { VALOR, TRANSACAO, EVENTO }

    @TestConfiguration(proxyBeanMethods = false)
    static class ColaboradoresDeTeste {
        @Bean @Primary PoliticaControlada politicaControlada() { return new PoliticaControlada(); }
        @Bean @Primary RelogioContado relogioContado() { return new RelogioContado(); }
        @Bean @Primary GeradorContado geradorContado() { return new GeradorContado(); }
    }

    static class PoliticaControlada implements LimitesProcessamento {
        private final AtomicReference<Porta> proxima = new AtomicReference<>();
        private final AtomicInteger chamadas = new AtomicInteger();

        Porta armar(boolean falhar) {
            var porta = new Porta(new CountDownLatch(1), new CountDownLatch(1), falhar);
            proxima.set(porta);
            return porta;
        }

        void resetar() { chamadas.set(0); proxima.set(null); }

        @Override public BigDecimal limitePara(Currency moeda) {
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
                    throw new IllegalStateException("falha tecnica simulada");
                }
            }
            return new BigDecimal("100.00");
        }
    }

    private record Porta(CountDownLatch entrou, CountDownLatch liberar, boolean falhar) { }

    static class RelogioContado extends Clock {
        private final AtomicInteger chamadas = new AtomicInteger();
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() {
            chamadas.incrementAndGet();
            return Instant.parse("2026-09-30T12:00:01.123456Z");
        }
    }

    static class GeradorContado implements GeradorEventIdSaida {
        private final AtomicInteger chamadas = new AtomicInteger();
        @Override public UUID gerar() { chamadas.incrementAndGet(); return UUID.randomUUID(); }
    }
}
