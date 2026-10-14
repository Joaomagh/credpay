package br.com.credpay.processamento.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
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

@SpringBootTest(properties = "credpay.processamento.limites.BRL=100.00")
@Import(RegistrarEncerramentoService.class)
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RegistrarEncerramentoIntegrationTest {

    private static HikariDataSource poolDaFixture;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgresDaFixture()
            .withDatabaseName("credpay_registrar_encerramento")
            .withUsername("test").withPassword("test");

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

    @Autowired private RegistrarEncerramentoService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private HikariDataSource dataSource;

    @BeforeEach void capturarPool() { poolDaFixture = dataSource; }

    @Test
    void executar_deveCommitarRegistroERespostaPropria_quandoNaoExistirDecisao() {
        var transacao = UUID.randomUUID();
        var pedido = new PedidoEncerramentoRecebido(UUID.randomUUID(), transacao, UUID.randomUUID(),
                Instant.parse("2026-10-14T12:00:00.123456789Z"), transacao,
                PedidoEncerramentoRecebido.Motivo.FALHA_PERMANENTE_CONTROLADA, UUID.randomUUID());

        service.executar(pedido);

        var quantidade = jdbc.queryForObject("SELECT count(*) FROM encerramentos WHERE request_event_id = ?",
                Integer.class, pedido.eventId());
        assertThat(quantidade).withFailMessage("pedido deve permanecer apos retorno/commit").isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM encerramentos e JOIN outbox_encerramentos o
                  ON o.event_id = e.response_event_id AND o.aggregate_id = e.transaction_id
                WHERE e.request_event_id = ? AND e.resultado = 'CONFIRMADO'
                  AND o.published_at IS NULL
                """, Integer.class, pedido.eventId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processamentos", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_eventos", Integer.class)).isZero();
    }
}
