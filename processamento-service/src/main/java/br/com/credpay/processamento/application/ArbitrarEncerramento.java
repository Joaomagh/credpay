package br.com.credpay.processamento.application;

import java.util.Optional;
import java.util.Objects;
import java.util.UUID;

public final class ArbitrarEncerramento {
    private ArbitrarEncerramento() {
    }

    public static Resultado executar(UUID transactionId, UUID creationEventId,
            Optional<ProcessamentoRegistrado> decisaoExistente) {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(creationEventId, "creationEventId");
        Objects.requireNonNull(decisaoExistente, "decisaoExistente");
        if (decisaoExistente.isPresent()) {
            var original = decisaoExistente.orElseThrow();
            if (!original.transactionId().equals(transactionId) || !original.eventId().equals(creationEventId)) {
                throw new ConflitoProcessamentoException("identidade do encerramento divergente");
            }
            return new Resultado(Status.DECISAO_EXISTENTE, Optional.of(original.outputEventId()));
        }
        return new Resultado(Status.BLOQUEIO_NECESSARIO, Optional.empty());
    }

    // A confirmação externa exige bloqueio e outbox duráveis; esta regra não grava nenhum deles.
    public enum Status { BLOQUEIO_NECESSARIO, DECISAO_EXISTENTE }

    public record Resultado(Status status, Optional<UUID> outputEventId) {
    }
}
