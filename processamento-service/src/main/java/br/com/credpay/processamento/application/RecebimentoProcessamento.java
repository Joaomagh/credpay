package br.com.credpay.processamento.application;

public sealed interface RecebimentoProcessamento
        permits ProcessamentoRegistrado, CriacaoBloqueadaRegistrada {
}
