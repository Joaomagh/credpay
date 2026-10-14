package br.com.credpay.transacoes.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record RespostaEncerramentoRecebida(
        UUID eventId, UUID transactionId, UUID creationEventId, Instant occurredAt,
        UUID correlationId, UUID causationId, Resultado resultado, Optional<UUID> outputEventId) {

    public enum Resultado { CONFIRMADO, DECISAO_EXISTENTE }
}
