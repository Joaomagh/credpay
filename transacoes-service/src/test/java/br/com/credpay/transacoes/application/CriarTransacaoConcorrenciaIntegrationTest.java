package br.com.credpay.transacoes.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.Test;
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
class CriarTransacaoConcorrenciaIntegrationTest {

    private static final AtomicReference<HikariDataSource> POOL_ORIGINAL = new AtomicReference<>();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgresDaFixture()
            .withDatabaseName("credpay_test")
            .withUsername("test")
            .withPassword("test");

    private static final class PostgresDaFixture extends PostgreSQLContainer<PostgresDaFixture> {
        private PostgresDaFixture() {
            super("postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0");
        }

        @Override
        public void stop() {
            try {
                var pool = POOL_ORIGINAL.get();
                assertThat(pool).withFailMessage("fixture não capturou o pool original no teste").isNotNull();
                assertThat(isRunning()).withFailMessage("PostgreSQL já estava parado antes da observação de lifecycle").isTrue();
                System.out.println("CredPay fixture lifecycle: pool=" + pool.getPoolName()
                        + " closed=" + pool.isClosed() + " postgresRunning=true");
                assertThat(pool.isClosed()).withFailMessage("pool da fixture de concorrência deve fechar antes do PostgreSQL").isTrue();
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

    @Autowired
    private CriarTransacao criarTransacao;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private HikariDataSource dataSource;

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void executar_deveConvergirParaMesmaTransacao_quandoPrimeirasCriacoesForemConcorrentes()
            throws Exception {
        POOL_ORIGINAL.set(dataSource);
        var chave = UUID.fromString("be9da3ee-e87e-4304-8341-676ceb035133");
        var inicio = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var primeira = executor.submit(() -> criarDepoisDoSinal(chave, inicio));
            var segunda = executor.submit(() -> criarDepoisDoSinal(chave, inicio));

            inicio.countDown();
            var resultadoPrimeiro = primeira.get(15, TimeUnit.SECONDS);
            var resultadoSegundo = segunda.get(15, TimeUnit.SECONDS);

            assertThat(resultadoPrimeiro.id()).isEqualTo(resultadoSegundo.id());
            assertThat(resultadoPrimeiro.valor()).isEqualByComparingTo(resultadoSegundo.valor());
            assertThat(resultadoPrimeiro.moeda()).isEqualTo(resultadoSegundo.moeda());
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM idempotencias_transacao WHERE chave = ?",
                    Integer.class,
                    chave)).isEqualTo(1);
        }
    }

    private CriarTransacao.Resultado criarDepoisDoSinal(UUID chave, CountDownLatch inicio)
            throws InterruptedException {
        inicio.await();
        return criarTransacao.executar(
                chave, new BigDecimal("10.00"), Currency.getInstance("BRL"));
    }
}
