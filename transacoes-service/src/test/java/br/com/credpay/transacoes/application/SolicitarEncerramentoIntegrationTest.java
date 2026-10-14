package br.com.credpay.transacoes.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Import(SolicitarEncerramentoService.class)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SolicitarEncerramentoIntegrationTest {

    private static HikariDataSource poolDaFixture;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgresDaFixture()
            .withDatabaseName("credpay_solicitar_encerramento").withUsername("test").withPassword("test");

    private static final class PostgresDaFixture extends PostgreSQLContainer<PostgresDaFixture> {
        private PostgresDaFixture() {
            super("postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0");
        }

        @Override public void stop() {
            try {
                assertThat(poolDaFixture).isNotNull();
                assertThat(isRunning()).isTrue();
                assertThat(poolDaFixture.isClosed()).isTrue();
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

    @Autowired private CriarTransacao criar;
    @Autowired private SolicitarEncerramentoService solicitar;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private HikariDataSource dataSource;

    @BeforeEach void capturarPool() { poolDaFixture = dataSource; }

    @Test
    void executar_deveGravarPedidoEIntencao_semMudarTransacaoOuRespostaOriginal() {
        var chave = UUID.randomUUID();
        var original = criar.executar(chave, new BigDecimal("75.00"), Currency.getInstance("BRL"));
        var transacao = jdbc.queryForMap("SELECT * FROM transacoes");
        var criacao = jdbc.queryForMap("SELECT * FROM outbox_eventos");
        var historico = jdbc.queryForList("SELECT * FROM historico_transacoes");
        var pedido = UUID.randomUUID();

        solicitar.executar(pedido, original.id(), UUID.randomUUID());

        var quantidade = jdbc.queryForObject("SELECT count(*) FROM pedidos_encerramento WHERE event_id = ?",
                Integer.class, pedido);
        assertThat(quantidade).withFailMessage("pedido esperado1, observado%s", quantidade).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pedidos_encerramento p JOIN outbox_pedidos_encerramento o
                  ON o.event_id = p.event_id AND o.aggregate_id = p.transaction_id
                WHERE p.event_id = ? AND o.published_at IS NULL
                """, Integer.class, pedido)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT * FROM transacoes")).isEqualTo(transacao);
        assertThat(jdbc.queryForMap("SELECT * FROM outbox_eventos")).isEqualTo(criacao);
        assertThat(jdbc.queryForList("SELECT * FROM historico_transacoes")).isEqualTo(historico);
        assertThat(criar.executar(chave, new BigDecimal("75.0"), Currency.getInstance("BRL"))).isEqualTo(original);
    }
}
