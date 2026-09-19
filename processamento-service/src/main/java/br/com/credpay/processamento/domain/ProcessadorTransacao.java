package br.com.credpay.processamento.domain;

import java.math.BigDecimal;

public final class ProcessadorTransacao {

    private ProcessadorTransacao() {
    }

    public static StatusProcessamento processar(BigDecimal valor, BigDecimal limite) {
        if (valor.compareTo(limite) > 0) {
            return StatusProcessamento.REJEITADA;
        }
        return StatusProcessamento.APROVADA;
    }
}
