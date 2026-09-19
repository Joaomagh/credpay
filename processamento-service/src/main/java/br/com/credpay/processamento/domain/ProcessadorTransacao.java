package br.com.credpay.processamento.domain;

import java.math.BigDecimal;

public final class ProcessadorTransacao {

    private ProcessadorTransacao() {
    }

    public static StatusProcessamento processar(BigDecimal valor, BigDecimal limite) {
        if (valor == null) {
            throw new IllegalArgumentException("valor deve ser informado");
        }
        if (valor.compareTo(limite) > 0) {
            return StatusProcessamento.REJEITADA;
        }
        return StatusProcessamento.APROVADA;
    }
}
