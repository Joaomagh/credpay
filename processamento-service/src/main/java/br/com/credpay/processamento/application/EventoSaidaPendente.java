package br.com.credpay.processamento.application;

import java.time.Instant;
import java.util.UUID;

public record EventoSaidaPendente(
        UUID eventId,
        UUID aggregateId,
        String eventType,
        int eventVersion,
        String payload,
        Instant occurredAt) {
}
