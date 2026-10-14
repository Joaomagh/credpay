package br.com.credpay.transacoes.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PedidoEncerramentoRepository {
    Optional<PedidoEncerramentoRegistrado> buscarPorPedidoId(UUID pedidoId);

    boolean existePorTransactionId(UUID transactionId);

    List<UUID> buscarCriacoesOriginais(UUID transactionId);

    void inserir(PedidoEncerramentoRegistrado pedido);
}
