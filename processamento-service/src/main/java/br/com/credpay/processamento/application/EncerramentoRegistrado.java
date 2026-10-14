package br.com.credpay.processamento.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

public record EncerramentoRegistrado(PedidoEncerramentoRecebido pedido, Resultado resultado,
        UUID responseEventId, Instant respondedAt, Optional<UUID> outputEventId) {

    public EncerramentoRegistrado {
        respondedAt = respondedAt.truncatedTo(ChronoUnit.MICROS);
    }

    public enum Resultado { CONFIRMADO, DECISAO_EXISTENTE }
}
