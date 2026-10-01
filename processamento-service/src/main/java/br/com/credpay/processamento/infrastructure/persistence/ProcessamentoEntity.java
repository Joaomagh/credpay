package br.com.credpay.processamento.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import br.com.credpay.processamento.application.ProcessamentoRegistrado;
import br.com.credpay.processamento.domain.StatusProcessamento;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "processamentos")
class ProcessamentoEntity {

    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "transaction_id", nullable = false, unique = true)
    private UUID transactionId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "event_version", nullable = false)
    private int eventVersion;

    @Column(name = "event_occurred_epoch_second", nullable = false)
    private long eventOccurredEpochSecond;

    @Column(name = "event_occurred_nano", nullable = false)
    private int eventOccurredNano;

    @Column(name = "correlation_id", nullable = false)
    private UUID correlationId;

    @Column(name = "input_status", nullable = false, length = 16)
    private String inputStatus;

    @Column(nullable = false, columnDefinition = "numeric")
    private BigDecimal valor;

    @Column(nullable = false, length = 3)
    private String moeda;

    @Column(name = "limite_aplicado", nullable = false, columnDefinition = "numeric")
    private BigDecimal limiteAplicado;

    @Enumerated(EnumType.STRING)
    @Column(name = "resultado", nullable = false, length = 16)
    private StatusProcessamento resultado;

    @Column(name = "processed_at", nullable = false, columnDefinition = "timestamptz(6)")
    private Instant processedAt;

    @Column(name = "output_event_id", nullable = false, unique = true)
    private UUID outputEventId;

    protected ProcessamentoEntity() {
    }

    ProcessamentoEntity(ProcessamentoRegistrado processamento) {
        eventId = processamento.eventId();
        transactionId = processamento.transactionId();
        eventType = processamento.eventType();
        eventVersion = processamento.eventVersion();
        eventOccurredEpochSecond = processamento.occurredAt().getEpochSecond();
        eventOccurredNano = processamento.occurredAt().getNano();
        correlationId = processamento.correlationId();
        inputStatus = processamento.inputStatus();
        valor = processamento.valor();
        moeda = processamento.moeda().getCurrencyCode();
        limiteAplicado = processamento.limiteAplicado();
        resultado = processamento.status();
        processedAt = processamento.processedAt();
        outputEventId = processamento.outputEventId();
    }

    ProcessamentoRegistrado paraAplicacao() {
        return new ProcessamentoRegistrado(
                eventId,
                transactionId,
                eventType,
                eventVersion,
                Instant.ofEpochSecond(eventOccurredEpochSecond, eventOccurredNano),
                correlationId,
                inputStatus,
                valor,
                Currency.getInstance(moeda),
                limiteAplicado,
                resultado,
                processedAt,
                outputEventId);
    }
}
