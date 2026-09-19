package br.com.credpay.processamento.domain;

import java.math.BigDecimal;

public final class ProcessadorTransacao {

    private ProcessadorTransacao() {
    }

    public static StatusProcessamento processar(BigDecimal valor, BigDecimal limite) {
        return StatusProcessamento.APROVADA;
    }
}
