package br.com.credpay.processamento.domain;

import java.math.BigDecimal;

public final class ProcessadorTransacao {

    private ProcessadorTransacao() {
    }

    public static StatusProcessamento processar(BigDecimal valor, BigDecimal limite) {
        if (valor == null) {
            throw new IllegalArgumentException("valor deve ser informado");
        }
        if (limite == null) {
            throw new IllegalArgumentException("limite deve ser informado");
        }
        if (valor.signum() <= 0) {
            throw new IllegalArgumentException("valor deve ser maior que zero");
        }
        if (limite.signum() <= 0) {
            throw new IllegalArgumentException("limite deve ser maior que zero");
        }
        if (valor.compareTo(limite) > 0) {
            return StatusProcessamento.REJEITADA;
        }
        return StatusProcessamento.APROVADA;
    }
}
