package br.com.credpay.processamento.domain;

import java.math.BigDecimal;
import java.util.Currency;

public final class ResultadoProcessamento {

    private final BigDecimal valor;
    private final Currency moeda;
    private final BigDecimal limiteAplicado;
    private final StatusProcessamento status;

    private ResultadoProcessamento(
            BigDecimal valor, Currency moeda, BigDecimal limiteAplicado, StatusProcessamento status) {
        this.valor = valor;
        this.moeda = moeda;
        this.limiteAplicado = limiteAplicado;
        this.status = status;
    }

    public static ResultadoProcessamento decidir(BigDecimal valor, Currency moeda, BigDecimal limite) {
        if (moeda == null) {
            throw new IllegalArgumentException("moeda deve ser informada");
        }
        var status = ProcessadorTransacao.processar(valor, limite);
        return new ResultadoProcessamento(valor, moeda, limite, status);
    }

    public BigDecimal valor() {
        return valor;
    }

    public Currency moeda() {
        return moeda;
    }

    public BigDecimal limiteAplicado() {
        return limiteAplicado;
    }

    public StatusProcessamento status() {
        return status;
    }
}
