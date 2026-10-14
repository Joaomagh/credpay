package br.com.credpay.processamento.application;

import java.util.Optional;
import java.util.UUID;

public interface EncerramentoRepository {
    Optional<EncerramentoRegistrado> buscarPorPedidoId(UUID eventId);

    boolean existePorTransactionId(UUID transactionId);

    void inserir(EncerramentoRegistrado registro);
}
