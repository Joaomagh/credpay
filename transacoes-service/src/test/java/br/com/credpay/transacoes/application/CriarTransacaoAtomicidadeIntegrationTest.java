package br.com.credpay.transacoes.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class CriarTransacaoAtomicidadeIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            "postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0")
            .withDatabaseName("credpay_test")
            .withUsername("test")
            .withPassword("test");

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

    @MockitoBean
    private OutboxRepository outboxRepository;

    @Test
    void executar_deveReverterCriacao_quandoOutboxFalhar() {
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
