package br.com.credpay.processamento.application;

import java.util.Optional;
import java.util.UUID;

public interface CriacaoBloqueadaRepository {

    Optional<BloqueioConfirmado> buscarPedidoConfirmadoPorTransactionId(UUID transactionId);

    Optional<CriacaoBloqueadaRegistrada> buscarRecebimentoPorEventId(UUID eventId);

    void inserir(CriacaoBloqueadaRegistrada recebimento);

    record BloqueioConfirmado(UUID requestEventId, UUID creationEventId) { }
}
