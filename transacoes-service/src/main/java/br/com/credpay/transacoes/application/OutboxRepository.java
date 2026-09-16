package br.com.credpay.transacoes.application;

public interface OutboxRepository {

    void adicionar(EventoOutbox evento);
}
