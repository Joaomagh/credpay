package br.com.credpay.processamento.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

public record TransacaoCriadaRecebida(
        UUID eventId,
        UUID transactionId,
        Instant occurredAt,
        UUID correlationId,
        BigDecimal valor,
        Currency moeda) {

    public TransacaoCriadaRecebida {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(valor, "valor");
        Objects.requireNonNull(moeda, "moeda");
        if (!correlationId.equals(transactionId)) {
            throw new IllegalArgumentException("correlationId deve corresponder a transactionId");
        }
        if (valor.signum() <= 0) {
            throw new IllegalArgumentException("valor deve ser maior que zero");
        }
    }
}
