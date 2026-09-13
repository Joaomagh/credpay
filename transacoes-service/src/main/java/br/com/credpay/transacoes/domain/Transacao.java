package br.com.credpay.transacoes.domain;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

public final class Transacao {

    private final UUID id;
    private final BigDecimal valor;
    private final Currency moeda;
    private final StatusTransacao status;

    private Transacao(UUID id, BigDecimal valor, Currency moeda, StatusTransacao status) {
        this.id = id;
        this.valor = valor;
        this.moeda = moeda;
        this.status = status;
    }

    public static Transacao criar(UUID id, BigDecimal valor, Currency moeda) {
        if (id == null) {
            throw new IllegalArgumentException("id deve ser informado");
        }

        if (valor == null) {
            throw new IllegalArgumentException("valor deve ser informado");
        }

        if (valor.signum() <= 0) {
            throw new IllegalArgumentException("valor deve ser maior que zero");
        }

        if (moeda == null) {
            throw new IllegalArgumentException("moeda deve ser informada");
        }

        return new Transacao(id, valor, moeda, StatusTransacao.PENDENTE);
    }

    public UUID id() {
        return id;
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
