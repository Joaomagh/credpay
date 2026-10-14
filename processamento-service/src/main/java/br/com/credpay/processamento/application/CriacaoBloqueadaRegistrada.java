package br.com.credpay.processamento.application;

import java.util.UUID;

public record CriacaoBloqueadaRegistrada(TransacaoCriadaRecebida entrada, UUID requestEventId)
        implements RecebimentoProcessamento {

    public boolean correspondeA(TransacaoCriadaRecebida outra) {
        return entrada.eventId().equals(outra.eventId())
                && entrada.transactionId().equals(outra.transactionId())
                && entrada.occurredAt().equals(outra.occurredAt())
                && entrada.correlationId().equals(outra.correlationId())
                && entrada.valor().compareTo(outra.valor()) == 0
                && entrada.moeda().equals(outra.moeda());
    }
}
