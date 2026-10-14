package br.com.credpay.processamento.application;

import java.util.Optional;
import java.util.UUID;

public interface CriacaoBloqueadaRepository {

    Optional<UUID> buscarPedidoConfirmadoPorTransactionId(UUID transactionId);

    void inserir(CriacaoBloqueadaRegistrada recebimento);
}
