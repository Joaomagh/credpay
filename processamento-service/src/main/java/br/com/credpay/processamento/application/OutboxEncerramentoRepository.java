package br.com.credpay.processamento.application;

public interface OutboxEncerramentoRepository {
    void adicionar(EventoSaidaPendente evento);
}
