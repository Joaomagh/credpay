package br.com.credpay.transacoes.infrastructure.persistence;

import br.com.credpay.transacoes.application.EventoOutbox;
import br.com.credpay.transacoes.application.OutboxRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class OutboxJdbcRepository implements OutboxRepository {

    private final JdbcTemplate jdbcTemplate;

    OutboxJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void adicionar(EventoOutbox evento) {
        jdbcTemplate.update("""
                INSERT INTO outbox_eventos (
                    event_id,
                    aggregate_id,
                    event_type,
                    event_version,
                    payload,
                    occurred_at
                ) VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?)
                """,
                evento.eventId(),
                evento.aggregateId(),
                evento.eventType(),
                evento.eventVersion(),
                evento.payload(),
                Timestamp.from(evento.occurredAt()));
    }

    @Override
    public List<EventoOutbox> buscarPendentes(int limite) {
        return jdbcTemplate.query("""
                        SELECT event_id, aggregate_id, event_type, event_version,
                               payload::text, occurred_at
                        FROM outbox_eventos
                        WHERE published_at IS NULL
                        ORDER BY occurred_at, event_id
                        LIMIT ?
                        """,
                (resultado, linha) -> new EventoOutbox(
                        resultado.getObject("event_id", UUID.class),
                        resultado.getObject("aggregate_id", UUID.class),
                        resultado.getString("event_type"),
                        resultado.getInt("event_version"),
                        resultado.getString("payload"),
                        resultado.getTimestamp("occurred_at").toInstant()),
                limite);
    }

    @Override
    public void marcarPublicado(UUID eventId, Instant publicadoEm) {
        jdbcTemplate.update("""
                        UPDATE outbox_eventos
                        SET published_at = ?
                        WHERE event_id = ?
                          AND published_at IS NULL
                        """,
                Timestamp.from(publicadoEm),
                eventId);
    }
}
