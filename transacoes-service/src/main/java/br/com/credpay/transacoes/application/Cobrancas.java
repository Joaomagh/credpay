package br.com.credpay.transacoes.application;

import java.util.UUID;

import br.com.credpay.transacoes.domain.Cobranca;
import br.com.credpay.transacoes.domain.PedidoCobranca;

public interface Cobrancas {
    Cobranca criar(UUID contaId, UUID chave, PedidoCobranca pedido);
}
