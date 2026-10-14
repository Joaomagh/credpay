package br.com.credpay.transacoes.application;

public interface OutboxPedidoEncerramentoRepository {
    void adicionar(EventoOutbox evento);
}
