package br.com.credpay.processamento.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import java.util.Currency;
import java.time.Instant;

import br.com.credpay.processamento.application.CriacaoBloqueadaRegistrada;
import br.com.credpay.processamento.application.CriacaoBloqueadaRepository;
import br.com.credpay.processamento.application.TransacaoCriadaRecebida;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class CriacaoBloqueadaJdbcRepository implements CriacaoBloqueadaRepository {

    private final JdbcTemplate jdbc;

    CriacaoBloqueadaJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<BloqueioConfirmado> buscarPedidoConfirmadoPorTransactionId(UUID transactionId) {
        return jdbc.query("""
                SELECT request_event_id, creation_event_id FROM encerramentos
                WHERE transaction_id = ? AND resultado = 'CONFIRMADO'
                """, (resultado, linha) -> new BloqueioConfirmado(
                        resultado.getObject("request_event_id", UUID.class),
                        resultado.getObject("creation_event_id", UUID.class)),
                transactionId).stream().findFirst();
    }

    @Override
    public Optional<CriacaoBloqueadaRegistrada> buscarRecebimentoPorEventId(UUID eventId) {
        return jdbc.query("""
                SELECT event_id, transaction_id, event_occurred_epoch_second, event_occurred_nano,
                    correlation_id, valor, moeda, request_event_id
                FROM criacao_bloqueada_recebimentos WHERE event_id = ?
                """, (resultado, linha) -> new CriacaoBloqueadaRegistrada(
                        new TransacaoCriadaRecebida(resultado.getObject("event_id", UUID.class),
                                resultado.getObject("transaction_id", UUID.class),
                                Instant.ofEpochSecond(resultado.getLong("event_occurred_epoch_second"),
                                        resultado.getInt("event_occurred_nano")),
                                resultado.getObject("correlation_id", UUID.class),
                                resultado.getBigDecimal("valor"), Currency.getInstance(resultado.getString("moeda"))),
                        resultado.getObject("request_event_id", UUID.class)), eventId).stream().findFirst();
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
