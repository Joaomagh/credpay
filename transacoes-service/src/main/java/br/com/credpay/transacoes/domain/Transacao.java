package br.com.credpay.transacoes.domain;

import java.math.BigDecimal;
import java.util.Currency;

final class Transacao {

    private final StatusTransacao status;

    private Transacao(StatusTransacao status) {
        this.status = status;
    }

    static Transacao criar(BigDecimal valor, Currency moeda) {
        return new Transacao(StatusTransacao.PENDENTE);
    }

    StatusTransacao status() {
        return status;
    }
}
