package br.com.credpay.processamento.application;

public interface PublicadorEventoSaida {
    boolean publicar(EventoSaidaPendente evento);
}
