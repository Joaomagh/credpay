package br.com.credpay.processamento.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

import br.com.credpay.processamento.domain.StatusProcessamento;

public record ProcessamentoRegistrado(
        UUID eventId,
        UUID transactionId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        UUID correlationId,
        String inputStatus,
        BigDecimal valor,
        Currency moeda,
        BigDecimal limiteAplicado,
        StatusProcessamento status,
        Instant processedAt,
        UUID outputEventId) {

    public ProcessamentoRegistrado {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(inputStatus, "inputStatus");
        Objects.requireNonNull(valor, "valor");
        Objects.requireNonNull(moeda, "moeda");
        Objects.requireNonNull(limiteAplicado, "limiteAplicado");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(processedAt, "processedAt");
        Objects.requireNonNull(outputEventId, "outputEventId");
        processedAt = processedAt.truncatedTo(ChronoUnit.MICROS);
    }

    public boolean correspondeA(TransacaoCriadaRecebida entrada) {
        return eventId.equals(entrada.eventId())
                && transactionId.equals(entrada.transactionId())
                && eventType.equals("TransacaoCriada")
                && eventVersion == 1
                && occurredAt.equals(entrada.occurredAt())
                && correlationId.equals(entrada.correlationId())
                && inputStatus.equals("PENDENTE")
                && valor.compareTo(entrada.valor()) == 0
                && moeda.equals(entrada.moeda());
    }
}
