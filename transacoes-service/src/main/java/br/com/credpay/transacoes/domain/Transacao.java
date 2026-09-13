package br.com.credpay.transacoes.domain;

import java.math.BigDecimal;
import java.util.Currency;

public final class Transacao {

    private final BigDecimal valor;
    private final Currency moeda;
    private final StatusTransacao status;

    private Transacao(BigDecimal valor, Currency moeda, StatusTransacao status) {
        this.valor = valor;
        this.moeda = moeda;
        this.status = status;
    }

    public static Transacao criar(BigDecimal valor, Currency moeda) {
        if (valor == null) {
            throw new IllegalArgumentException("valor deve ser informado");
        }

        if (valor.signum() <= 0) {
            throw new IllegalArgumentException("valor deve ser maior que zero");
        }

        if (moeda == null) {
            throw new IllegalArgumentException("moeda deve ser informada");
        }

        return new Transacao(valor, moeda, StatusTransacao.PENDENTE);
    }

    public BigDecimal valor() {
        return valor;
    }

    public Currency moeda() {
        return moeda;
    }

    public StatusTransacao status() {
        return status;
    }
}
