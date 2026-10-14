package br.com.credpay.transacoes.application;

import java.util.List;
import java.util.UUID;

public interface PedidoEncerramentoRepository {
    List<UUID> buscarCriacoesOriginais(UUID transactionId);

    void inserir(PedidoEncerramentoRegistrado pedido);
}
