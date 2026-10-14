package br.com.credpay.transacoes.infrastructure.persistence;

import java.util.List;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import br.com.credpay.transacoes.application.EventoOutbox;
import br.com.credpay.transacoes.application.OutboxPedidoEncerramentoRepository;
import br.com.credpay.transacoes.application.PedidoEncerramentoRegistrado;
import br.com.credpay.transacoes.application.PedidoEncerramentoRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PedidoEncerramentoJdbcRepository
        implements PedidoEncerramentoRepository, OutboxPedidoEncerramentoRepository {

    private final JdbcTemplate jdbc;

    public PedidoEncerramentoJdbcRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<PedidoEncerramentoRegistrado> buscarPorPedidoId(UUID pedidoId) {
        return jdbc.query("SELECT * FROM pedidos_encerramento WHERE event_id = ?", (rs, row) ->
                new PedidoEncerramentoRegistrado(rs.getObject("event_id", UUID.class),
                        rs.getObject("transaction_id", UUID.class), rs.getObject("creation_event_id", UUID.class),
                        Instant.ofEpochSecond(rs.getLong("occurred_epoch_second"), rs.getInt("occurred_nano")),
                        rs.getObject("correlation_id", UUID.class), rs.getString("motivo"),
                        rs.getObject("evidence_ref", UUID.class)), pedidoId).stream().findFirst();
    }

    @Override
    public boolean existePorTransactionId(UUID transactionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pedidos_encerramento WHERE transaction_id = ?)",
                Boolean.class, transactionId));
    }

    @Override
    public List<UUID> buscarCriacoesOriginais(UUID transactionId) {
        return jdbc.query("""
                SELECT event_id FROM outbox_eventos
                WHERE aggregate_id = ? AND event_type = 'TransacaoCriada' AND event_version = 1
                """, (rs, row) -> rs.getObject("event_id", UUID.class), transactionId);
    }

    @Override
    public void inserir(PedidoEncerramentoRegistrado pedido) {
        jdbc.update("""
                INSERT INTO pedidos_encerramento
                  (event_id, transaction_id, creation_event_id, occurred_epoch_second, occurred_nano,
                   correlation_id, motivo, evidence_ref)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, pedido.eventId(), pedido.transactionId(), pedido.creationEventId(),
                pedido.occurredAt().getEpochSecond(), pedido.occurredAt().getNano(), pedido.correlationId(),
                pedido.motivo(), pedido.evidenceRef());
    }

    @Override
    public void adicionar(EventoOutbox evento) {
        jdbc.update("""
                INSERT INTO outbox_pedidos_encerramento
                  (event_id, aggregate_id, event_type, event_version, payload, occurred_epoch_second, occurred_nano)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?)
                """, evento.eventId(), evento.aggregateId(), evento.eventType(), evento.eventVersion(),
                evento.payload(), evento.occurredAt().getEpochSecond(), evento.occurredAt().getNano());
    }
}
