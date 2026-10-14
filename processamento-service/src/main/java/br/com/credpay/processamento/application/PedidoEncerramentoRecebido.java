package br.com.credpay.processamento.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PedidoEncerramentoRecebido(
        UUID eventId, UUID transactionId, UUID creationEventId, Instant occurredAt,
        UUID correlationId, Motivo motivo, UUID evidenceRef) {

    public PedidoEncerramentoRecebido {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(creationEventId, "creationEventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(motivo, "motivo");
        Objects.requireNonNull(evidenceRef, "evidenceRef");
        if (!transactionId.equals(correlationId)) {
            throw new IllegalArgumentException("correlationId deve corresponder a transactionId");
        }
    }

    public enum Motivo {
        FALHA_PERMANENTE_CONTROLADA
    }
}
