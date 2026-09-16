package br.com.credpay.transacoes.application;

import java.time.Instant;
import java.util.UUID;

public record EventoOutbox(
        UUID eventId,
        UUID aggregateId,
        String eventType,
        int eventVersion,
        String payload,
        Instant occurredAt) {
}
