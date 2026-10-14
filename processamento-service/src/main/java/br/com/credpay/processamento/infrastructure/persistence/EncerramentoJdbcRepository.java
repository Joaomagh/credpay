package br.com.credpay.processamento.infrastructure.persistence;

import java.sql.Timestamp;

import br.com.credpay.processamento.application.EncerramentoRegistrado;
import br.com.credpay.processamento.application.EncerramentoRepository;
import br.com.credpay.processamento.application.EventoSaidaPendente;
import br.com.credpay.processamento.application.OutboxEncerramentoRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class EncerramentoJdbcRepository implements EncerramentoRepository, OutboxEncerramentoRepository {

    private final JdbcTemplate jdbc;

    EncerramentoJdbcRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

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
