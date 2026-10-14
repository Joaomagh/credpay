package br.com.credpay.transacoes.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record RespostaEncerramentoRecebida(
        UUID eventId, UUID transactionId, UUID creationEventId, Instant occurredAt,
        UUID correlationId, UUID causationId, Resultado resultado, Optional<UUID> outputEventId) {

    public RespostaEncerramentoRecebida {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(creationEventId, "creationEventId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(causationId, "causationId");
        Objects.requireNonNull(resultado, "resultado");
        Objects.requireNonNull(outputEventId, "outputEventId");
        if (!correlationId.equals(transactionId)) {
            throw new IllegalArgumentException("correlationId deve corresponder a transactionId");
        }
        if (outputEventId.isPresent() != (resultado == Resultado.DECISAO_EXISTENTE)) {
            throw new IllegalArgumentException("outputEventId deve existir somente em DECISAO_EXISTENTE");
        }
    }

    public enum Resultado { CONFIRMADO, DECISAO_EXISTENTE }
}
