package br.com.credpay.transacoes.domain;

import java.math.BigDecimal;
import java.util.Currency;

final class Transacao {

    private final StatusTransacao status;

    private Transacao(StatusTransacao status) {
        this.status = status;
    }

    static Transacao criar(BigDecimal valor, Currency moeda) {
        if (valor == null) {
            throw new IllegalArgumentException("valor deve ser informado");
        }

        if (valor.signum() <= 0) {
            throw new IllegalArgumentException("valor deve ser maior que zero");
        }

        if (moeda == null) {
            throw new IllegalArgumentException("moeda deve ser informada");
        }

        return new Transacao(StatusTransacao.PENDENTE);
    }

    StatusTransacao status() {
        return status;
    }
}
