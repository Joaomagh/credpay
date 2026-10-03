package br.com.credpay.transacoes.application;

import java.time.Instant;
import java.util.UUID;

import br.com.credpay.transacoes.domain.StatusTransacao;

public record TransicaoRecebida(
        UUID eventId,
        UUID transactionId,
        String eventType,
        int eventVersion,
        UUID correlationId,
        UUID causationId,
        StatusTransacao estadoAnterior,
        StatusTransacao estadoFinal,
        String origem,
        Instant occurredAt,
        Instant aplicadoEm) {
}
