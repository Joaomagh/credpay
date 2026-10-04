package br.com.credpay.transacoes.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class CriarTransacaoAtomicidadeIntegrationTest {

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
                assertThat(pool).withFailMessage("fixture Atomicidade não capturou o pool original").isNotNull();
                assertThat(isRunning()).withFailMessage("PostgreSQL já estava parado antes da observação de Atomicidade").isTrue();
                System.out.println("CredPay fixture lifecycle Atomicidade: pool=" + pool.getPoolName()
                        + " closed=" + pool.isClosed() + " postgresRunning=true");
                assertThat(pool.isClosed()).withFailMessage("pool da fixture Atomicidade deve fechar antes do PostgreSQL").isTrue();
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

    @MockitoBean
    private OutboxRepository outboxRepository;

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void executar_deveReverterCriacao_quandoOutboxFalhar() {
        POOL_ORIGINAL.set(dataSource);
        doThrow(new IllegalStateException("falha simulada da outbox"))
                .when(outboxRepository).adicionar(any(EventoOutbox.class));

        assertThatThrownBy(() -> criarTransacao.executar(
                UUID.randomUUID(),
                new BigDecimal("10.00"),
                Currency.getInstance("BRL")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("falha simulada da outbox");

        assertThat(contar("transacoes")).isZero();
        assertThat(contar("idempotencias_transacao")).isZero();
        assertThat(contar("outbox_eventos")).isZero();
    }

    private int contar(String tabela) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + tabela, Integer.class);
    }
}
