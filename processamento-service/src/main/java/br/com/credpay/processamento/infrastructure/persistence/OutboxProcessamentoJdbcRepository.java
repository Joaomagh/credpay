package br.com.credpay.processamento.infrastructure.persistence;

import java.sql.Timestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import br.com.credpay.processamento.application.EventoSaidaPendente;
import br.com.credpay.processamento.application.OutboxProcessamentoRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class OutboxProcessamentoJdbcRepository implements OutboxProcessamentoRepository {

    private final JdbcTemplate jdbcTemplate;

    OutboxProcessamentoJdbcRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void adicionar(EventoSaidaPendente evento) {
        jdbcTemplate.update("""
                INSERT INTO outbox_eventos (
                    event_id, aggregate_id, event_type, event_version,
                    payload, occurred_at
                ) VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?)
                """,
                evento.eventId(), evento.aggregateId(), evento.eventType(),
                evento.eventVersion(), evento.payload(), Timestamp.from(evento.occurredAt()));
    }

    @Override
    public Optional<EventoSaidaPendente> buscarPorEventId(UUID eventId) {
        List<EventoSaidaPendente> encontrados = jdbcTemplate.query("""
                        SELECT event_id, aggregate_id, event_type, event_version,
                               payload::text, occurred_at
                        FROM outbox_eventos
                        WHERE event_id = ?
                        """,
                this::mapear,
                eventId);
        return encontrados.stream().findFirst();
    }

    @Override
    public List<EventoSaidaPendente> buscarPendentes(int limite) {
        return jdbcTemplate.query("""
                        SELECT event_id, aggregate_id, event_type, event_version,
                               payload::text, occurred_at
                        FROM outbox_eventos
                        WHERE published_at IS NULL
                        ORDER BY occurred_at, event_id
                        LIMIT ?
                        """,
                this::mapear,
                limite);
    }

    @Override
    public void marcarPublicado(UUID eventId, Instant publicadoEm) {
        jdbcTemplate.update("""
                        UPDATE outbox_eventos
                        SET published_at = ?
                        WHERE event_id = ? AND published_at IS NULL
                        """,
                Timestamp.from(publicadoEm), eventId);
    }

    private EventoSaidaPendente mapear(ResultSet resultado, int linha) throws SQLException {
        return new EventoSaidaPendente(
                resultado.getObject("event_id", UUID.class),
                resultado.getObject("aggregate_id", UUID.class),
                resultado.getString("event_type"),
                resultado.getInt("event_version"),
                resultado.getString("payload"),
                resultado.getTimestamp("occurred_at").toInstant());
    }
}
