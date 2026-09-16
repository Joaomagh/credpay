package br.com.credpay.transacoes.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import br.com.credpay.transacoes.application.EventoOutbox;
import br.com.credpay.transacoes.application.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class OutboxRepositoryIntegrationTest {

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
    private OutboxRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void adicionar_devePersistirEventoPendente_preservandoContrato() throws Exception {
        var eventId = UUID.fromString("6dc8d48d-5b20-4ee9-ac7e-832e421121aa");
        var aggregateId = UUID.fromString("7b8b61c2-9f63-4d74-9f8d-89cb52de0ed9");
        var occurredAt = Instant.parse("2026-09-16T12:00:00Z");
        var payload = """
                {
                  "eventId":"6dc8d48d-5b20-4ee9-ac7e-832e421121aa",
                  "eventType":"TransacaoCriada",
                  "eventVersion":1
                }
                """;
        var evento = new EventoOutbox(
                eventId, aggregateId, "TransacaoCriada", 1, payload, occurredAt);

        new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> outboxRepository.adicionar(evento));

        var persistido = jdbcTemplate.queryForObject("""
                        SELECT aggregate_id, event_type, event_version, payload::text,
                               occurred_at, published_at
                        FROM outbox_eventos
                        WHERE event_id = ?
                        """,
                (resultado, linha) -> new EventoPersistido(
                        resultado.getObject("aggregate_id", UUID.class),
                        resultado.getString("event_type"),
                        resultado.getInt("event_version"),
                        resultado.getString("payload"),
                        resultado.getTimestamp("occurred_at").toInstant(),
                        resultado.getTimestamp("published_at")),
                eventId);

        assertThat(persistido.aggregateId()).isEqualTo(aggregateId);
        assertThat(persistido.eventType()).isEqualTo("TransacaoCriada");
        assertThat(persistido.eventVersion()).isEqualTo(1);
        assertThat(objectMapper.readTree(persistido.payload()))
                .isEqualTo(objectMapper.readTree(payload));
        assertThat(persistido.occurredAt()).isEqualTo(occurredAt);
        assertThat(persistido.publishedAt()).isNull();
    }

    private record EventoPersistido(
            UUID aggregateId,
            String eventType,
            int eventVersion,
            String payload,
            Instant occurredAt,
            Timestamp publishedAt) {
    }
}
