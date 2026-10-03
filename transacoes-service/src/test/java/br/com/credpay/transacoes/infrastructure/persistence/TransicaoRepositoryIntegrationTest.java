package br.com.credpay.transacoes.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.UUID;

import br.com.credpay.transacoes.application.TransicaoRecebida;
import br.com.credpay.transacoes.application.TransicaoRepository;
import br.com.credpay.transacoes.domain.StatusTransacao;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class TransicaoRepositoryIntegrationTest {

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

    @Autowired
    private TransicaoRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void registrar_deveConfirmarStatusEHistoricoPreservandoDadosEInstantes() {
        var transicao = preparar(StatusTransacao.APROVADA);

        repository.registrar(transicao);

        assertThat(repository.buscarPorEvento(transicao.eventId())).contains(transicao);
        assertThat(jdbc.queryForObject("SELECT status FROM transacoes WHERE id = ?",
                String.class, transicao.transactionId())).isEqualTo("APROVADA");
        assertThat(jdbc.queryForObject("SELECT valor FROM transacoes WHERE id = ?",
                BigDecimal.class, transicao.transactionId())).isEqualTo(new BigDecimal("123.450"));
        assertThat(jdbc.queryForObject("SELECT moeda FROM transacoes WHERE id = ?",
                String.class, transicao.transactionId())).isEqualTo(Currency.getInstance("BRL").getCurrencyCode());
    }

    private TransicaoRecebida preparar(StatusTransacao resultado) {
        var id = UUID.randomUUID();
        var causa = UUID.randomUUID();
        jdbc.update("INSERT INTO transacoes (id, valor, moeda, status) VALUES (?, 123.450, 'BRL', 'PENDENTE')", id);
        jdbc.update("""
                INSERT INTO outbox_eventos (event_id, aggregate_id, event_type, event_version, payload, occurred_at)
                VALUES (?, ?, 'TransacaoCriada', 1, '{}'::jsonb, NOW())
                """, causa, id);
        return new TransicaoRecebida(UUID.randomUUID(), id, "TransacaoProcessada", 1, id, causa,
                StatusTransacao.PENDENTE, resultado, "processamento-service/TransacaoProcessada.v1",
                Instant.parse("2026-10-03T12:00:00.123456789Z"),
                Instant.parse("2026-10-03T12:00:01.654321Z").truncatedTo(ChronoUnit.MICROS));
    }
}
