package br.com.credpay.processamento.application;

import java.util.UUID;

@FunctionalInterface
public interface GeradorEventIdSaida {
    UUID gerar();
}
