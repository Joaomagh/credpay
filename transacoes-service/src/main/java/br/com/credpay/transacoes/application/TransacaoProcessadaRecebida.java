package br.com.credpay.transacoes.application;

import java.time.Instant;
import java.util.UUID;
import java.util.Objects;

import br.com.credpay.transacoes.domain.StatusTransacao;

public record TransacaoProcessadaRecebida(
        UUID eventId,
        UUID transactionId,
        Instant occurredAt,
        UUID correlationId,
        UUID causationId,
        StatusTransacao status) {

    public TransacaoProcessadaRecebida {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(causationId, "causationId");
        Objects.requireNonNull(status, "status");
        if (!correlationId.equals(transactionId)) {
            throw new IllegalArgumentException("correlationId deve corresponder a transactionId");
        }
        if (status != StatusTransacao.APROVADA && status != StatusTransacao.REJEITADA) {
            throw new IllegalArgumentException("resultado deve ser APROVADA ou REJEITADA");
        }
    }
}
