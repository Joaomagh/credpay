package br.com.credpay.transacoes.application;

public interface PublicadorEvento {

    boolean publicar(EventoOutbox evento);
}
