package br.com.credpay.transacoes.infrastructure.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import br.com.credpay.transacoes.application.TransicaoRecebida;
import br.com.credpay.transacoes.application.TransicaoRecusadaException;
import br.com.credpay.transacoes.application.TransicaoRepository;
import br.com.credpay.transacoes.domain.StatusTransacao;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class TransicaoJdbcRepository implements TransicaoRepository {

    private final JdbcTemplate jdbc;

    TransicaoJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bloquearIdentidades(UUID eventId, UUID transactionId) {
        Stream.of(eventId, transactionId)
                .map(id -> jdbc.queryForObject("SELECT hashtextextended(CAST(? AS text), 0)",
                        Long.class, id.toString()))
                .distinct().sorted()
                .forEach(chave -> jdbc.queryForObject(
                        "SELECT 1 FROM pg_advisory_xact_lock(CAST(? AS bigint))", Long.class, chave));
    }

    @Override
    @Transactional
    public void registrar(TransicaoRecebida transicao) {
        var atualizadas = jdbc.update("""
                UPDATE transacoes SET status = ?
                WHERE id = ? AND status = 'PENDENTE'
                AND EXISTS (
                    SELECT 1 FROM outbox_eventos
                    WHERE event_id = ? AND aggregate_id = transacoes.id
                    AND event_type = 'TransacaoCriada' AND event_version = 1
                )
                """, transicao.estadoFinal().name(), transicao.transactionId(), transicao.causationId());
        if (atualizadas != 1) {
            throw new TransicaoRecusadaException();
        }
        jdbc.update("""
                INSERT INTO historico_transacoes (
                    event_id, transaction_id, event_type, event_version, correlation_id, causation_id,
                    estado_anterior, estado_final, origem, occurred_at_epoch_second, occurred_at_nano, aplicado_em
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, transicao.eventId(), transicao.transactionId(), transicao.eventType(),
                transicao.eventVersion(), transicao.correlationId(), transicao.causationId(),
                transicao.estadoAnterior().name(), transicao.estadoFinal().name(), transicao.origem(),
                transicao.occurredAt().getEpochSecond(), transicao.occurredAt().getNano(),
                Timestamp.from(transicao.aplicadoEm().truncatedTo(ChronoUnit.MICROS)));
    }

    @Override
    public Optional<TransicaoRecebida> buscarPorEvento(UUID eventId) {
        return jdbc.query("""
                SELECT event_id, transaction_id, event_type, event_version, correlation_id, causation_id,
                       estado_anterior, estado_final, origem, occurred_at_epoch_second, occurred_at_nano, aplicado_em
                FROM historico_transacoes WHERE event_id = ?
                """, (rs, linha) -> new TransicaoRecebida(
                rs.getObject("event_id", UUID.class), rs.getObject("transaction_id", UUID.class),
                rs.getString("event_type"), rs.getInt("event_version"),
                rs.getObject("correlation_id", UUID.class), rs.getObject("causation_id", UUID.class),
                StatusTransacao.valueOf(rs.getString("estado_anterior")),
                StatusTransacao.valueOf(rs.getString("estado_final")), rs.getString("origem"),
                Instant.ofEpochSecond(rs.getLong("occurred_at_epoch_second"), rs.getInt("occurred_at_nano")),
                rs.getTimestamp("aplicado_em").toInstant()), eventId).stream().findFirst();
    }

    @Override
    public boolean existeCriacao(UUID causationId, UUID transactionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM outbox_eventos
                    WHERE event_id = ? AND aggregate_id = ?
                    AND event_type = 'TransacaoCriada' AND event_version = 1
                )
                """, Boolean.class, causationId, transactionId));
    }
}
