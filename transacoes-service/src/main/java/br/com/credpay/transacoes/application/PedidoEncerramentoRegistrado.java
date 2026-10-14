package br.com.credpay.transacoes.application;

import java.time.Instant;
import java.util.UUID;

public record PedidoEncerramentoRegistrado(
        UUID eventId,
        UUID transactionId,
        UUID creationEventId,
        Instant occurredAt,
        UUID correlationId,
        String motivo,
        UUID evidenceRef) {
}
