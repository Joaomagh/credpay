package br.com.credpay.processamento;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.credpay.processamento.application.ProcessamentoRepository;
import br.com.credpay.processamento.application.RegistrarProcessamentoService;
import br.com.credpay.processamento.application.TransacaoCriadaRecebida;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "credpay.processamento.limites.BRL=100.00")
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
class ProcessamentoServiceApplicationTest {

    private static final List<HikariDataSource> POOLS_ORIGINAIS = new ArrayList<>();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgresDaFixture()
            .withDatabaseName("credpay_processamento_test")
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
                assertThat(pools).withFailMessage("fixture aplicação não capturou seus pools originais").isNotEmpty();
                assertThat(isRunning()).withFailMessage("PostgreSQL parou antes da observação da aplicação").isTrue();
                pools.forEach(pool -> System.out.println("CredPay fixture lifecycle Aplicacao: pool="
                        + pool.getPoolName() + " closed=" + pool.isClosed() + " postgresRunning=true"));
                assertThat(pools.stream().allMatch(HikariDataSource::isClosed))
                        .withFailMessage("todos os pools da aplicação devem fechar antes do PostgreSQL").isTrue();
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
    private TestRestTemplate http;

    @Autowired
    private RegistrarProcessamentoService processar;

    @Autowired
    private ProcessamentoRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private HikariDataSource dataSource;

    @BeforeEach
    void capturarPoolOriginal() {
        POOLS_ORIGINAIS.add(dataSource);
    }

    @Test
    void health_deveResponderUp_quandoAplicacaoIniciar() {
        var resposta = http.getForEntity("/actuator/health", String.class);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resposta.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void processar_deveConfirmarPrimeiroResultadoEReusarEmReplay() {
        var transactionId = UUID.randomUUID();
        var eventId = UUID.randomUUID();
        var entrada = new TransacaoCriadaRecebida(eventId, transactionId,
                Instant.parse("2026-09-30T12:00:00.123456789Z"), transactionId,
                new BigDecimal("75.0"), Currency.getInstance("BRL"));

        var original = processar.executar(entrada);
        var persistido = repository.buscarPorEventId(eventId).orElseThrow();
        var equivalente = new TransacaoCriadaRecebida(eventId, transactionId,
                entrada.occurredAt(), transactionId,
                new BigDecimal("75.00"), Currency.getInstance("BRL"));
        var replay = processar.executar(equivalente);
        Integer linhas = jdbc.queryForObject(
                "select count(*) from processamentos where transaction_id = ?",
                Integer.class, transactionId);

        assertThat(persistido).isEqualTo(original);
        assertThat(replay).isEqualTo(original);
        assertThat(linhas).isEqualTo(1);
    }
}
