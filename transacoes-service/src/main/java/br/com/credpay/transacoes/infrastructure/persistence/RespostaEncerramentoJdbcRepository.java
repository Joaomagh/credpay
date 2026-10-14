package br.com.credpay.transacoes.infrastructure.persistence;

import java.time.Instant;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import br.com.credpay.transacoes.application.RespostaEncerramentoRecebida;
import br.com.credpay.transacoes.application.RespostaEncerramentoRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class RespostaEncerramentoJdbcRepository implements RespostaEncerramentoRepository {
    private final JdbcTemplate jdbc;

    RespostaEncerramentoJdbcRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public void inserir(RespostaEncerramentoRecebida resposta) {
        jdbc.update("""
                INSERT INTO respostas_encerramento_recebidas
                  (event_id, transaction_id, creation_event_id, event_type, event_version, correlation_id,
                   causation_id, occurred_epoch_second, occurred_nano, resultado, output_event_id)
                VALUES (?, ?, ?, 'EncerramentoTransacaoRespondido', 1, ?, ?, ?, ?, ?, ?)
                """, resposta.eventId(), resposta.transactionId(), resposta.creationEventId(), resposta.correlationId(),
                resposta.causationId(), resposta.occurredAt().getEpochSecond(), resposta.occurredAt().getNano(),
                resposta.resultado().name(), resposta.outputEventId().orElse(null));
    }

    @Override public Optional<RespostaEncerramentoRecebida> buscarPorEvento(UUID eventId) {
        return jdbc.query("SELECT * FROM respostas_encerramento_recebidas WHERE event_id = ?",
                this::mapear, eventId).stream().findFirst();
    }

    @Override public Optional<RespostaEncerramentoRecebida> buscarPorTransactionId(UUID transactionId) {
        return jdbc.query("""
                SELECT r.* FROM pedidos_encerramento p
                JOIN respostas_encerramento_recebidas r ON r.causation_id = p.event_id
                WHERE p.transaction_id = ?
                """, this::mapear, transactionId).stream().findFirst();
    }

    private RespostaEncerramentoRecebida mapear(ResultSet rs, int row) throws SQLException {
        return new RespostaEncerramentoRecebida(rs.getObject("event_id", UUID.class),
                        rs.getObject("transaction_id", UUID.class), rs.getObject("creation_event_id", UUID.class),
                        Instant.ofEpochSecond(rs.getLong("occurred_epoch_second"), rs.getInt("occurred_nano")),
                        rs.getObject("correlation_id", UUID.class), rs.getObject("causation_id", UUID.class),
                        RespostaEncerramentoRecebida.Resultado.valueOf(rs.getString("resultado")),
                        Optional.ofNullable(rs.getObject("output_event_id", UUID.class)));
    }
}
