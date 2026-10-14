package br.com.credpay.processamento.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import br.com.credpay.processamento.application.CriacaoBloqueadaRegistrada;
import br.com.credpay.processamento.application.CriacaoBloqueadaRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class CriacaoBloqueadaJdbcRepository implements CriacaoBloqueadaRepository {

    private final JdbcTemplate jdbc;

    CriacaoBloqueadaJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UUID> buscarPedidoConfirmadoPorTransactionId(UUID transactionId) {
        return jdbc.query("""
                SELECT request_event_id FROM encerramentos
                WHERE transaction_id = ? AND resultado = 'CONFIRMADO'
                """, (resultado, linha) -> resultado.getObject("request_event_id", UUID.class),
                transactionId).stream().findFirst();
    }

    @Override
    public void inserir(CriacaoBloqueadaRegistrada recebimento) {
        var entrada = recebimento.entrada();
        jdbc.update("""
                INSERT INTO criacao_bloqueada_recebimentos (event_id, transaction_id,
                    event_type, event_version, event_occurred_epoch_second, event_occurred_nano,
                    correlation_id, input_status, valor, moeda, request_event_id, closure_result)
                VALUES (?, ?, 'TransacaoCriada', 1, ?, ?, ?, 'PENDENTE', ?, ?, ?, 'CONFIRMADO')
                """, entrada.eventId(), entrada.transactionId(), entrada.occurredAt().getEpochSecond(),
                entrada.occurredAt().getNano(), entrada.correlationId(), entrada.valor(),
                entrada.moeda().getCurrencyCode(), recebimento.requestEventId());
    }
}
