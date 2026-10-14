package br.com.credpay.processamento.infrastructure.persistence;

import java.sql.Timestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import br.com.credpay.processamento.application.EncerramentoRegistrado;
import br.com.credpay.processamento.application.EncerramentoRepository;
import br.com.credpay.processamento.application.EventoSaidaPendente;
import br.com.credpay.processamento.application.OutboxEncerramentoRepository;
import br.com.credpay.processamento.application.PedidoEncerramentoRecebido;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class EncerramentoJdbcRepository implements EncerramentoRepository, OutboxEncerramentoRepository {

    private final JdbcTemplate jdbc;

    EncerramentoJdbcRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<EncerramentoRegistrado> buscarPorPedidoId(UUID eventId) {
        return jdbc.query("""
                SELECT request_event_id, transaction_id, creation_event_id,
                    request_occurred_epoch_second, request_occurred_nano, correlation_id, motivo,
                    evidence_id, resultado, response_event_id, responded_at, output_event_id
                FROM encerramentos WHERE request_event_id = ?
                """, this::mapear, eventId).stream().findFirst();
    }

    @Override
    public boolean existePorTransactionId(UUID transactionId) {
        return jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM encerramentos WHERE transaction_id = ?)",
                Boolean.class, transactionId);
    }

    private EncerramentoRegistrado mapear(ResultSet rs, int linha) throws SQLException {
        var pedido = new PedidoEncerramentoRecebido(rs.getObject("request_event_id", UUID.class),
                rs.getObject("transaction_id", UUID.class), rs.getObject("creation_event_id", UUID.class),
                Instant.ofEpochSecond(rs.getLong("request_occurred_epoch_second"), rs.getInt("request_occurred_nano")),
                rs.getObject("correlation_id", UUID.class),
                PedidoEncerramentoRecebido.Motivo.valueOf(rs.getString("motivo")), rs.getObject("evidence_id", UUID.class));
        return new EncerramentoRegistrado(pedido, EncerramentoRegistrado.Resultado.valueOf(rs.getString("resultado")),
                rs.getObject("response_event_id", UUID.class), rs.getTimestamp("responded_at").toInstant(),
                Optional.ofNullable(rs.getObject("output_event_id", UUID.class)));
    }

    @Override
    public void inserir(EncerramentoRegistrado registro) {
        var pedido = registro.pedido();
        jdbc.update("""
                INSERT INTO encerramentos (request_event_id, transaction_id, creation_event_id,
                    event_type, event_version, request_occurred_epoch_second, request_occurred_nano,
                    correlation_id, motivo, evidence_id, resultado, response_event_id, responded_at, output_event_id)
                VALUES (?, ?, ?, 'EncerramentoTransacaoSolicitado', 1, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, pedido.eventId(), pedido.transactionId(), pedido.creationEventId(),
                pedido.occurredAt().getEpochSecond(), pedido.occurredAt().getNano(), pedido.correlationId(),
                pedido.motivo().name(), pedido.evidenceRef(), registro.resultado().name(),
                registro.responseEventId(), Timestamp.from(registro.respondedAt()), registro.outputEventId().orElse(null));
    }

    @Override
    public void adicionar(EventoSaidaPendente evento) {
        jdbc.update("""
                INSERT INTO outbox_encerramentos (event_id, aggregate_id, event_type,
                    event_version, payload, occurred_at)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?)
                """, evento.eventId(), evento.aggregateId(), evento.eventType(), evento.eventVersion(),
                evento.payload(), Timestamp.from(evento.occurredAt()));
    }
}
