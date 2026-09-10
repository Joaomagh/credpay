package br.com.credpay.transacoes.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;

import org.junit.jupiter.api.Test;

class TransacaoTest {

    @Test
    void criar_deveDefinirStatusPendente_quandoTransacaoForValida() {
        var valor = new BigDecimal("10.00");
        var moeda = Currency.getInstance("BRL");

        var transacao = Transacao.criar(valor, moeda);

        assertThat(transacao.status()).isEqualTo(StatusTransacao.PENDENTE);
    }

    @Test
    void criar_deveRejeitar_quandoValorForZero() {
        var moeda = Currency.getInstance("BRL");

        assertThatThrownBy(() -> Transacao.criar(BigDecimal.ZERO, moeda))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("valor deve ser maior que zero");
    }

    @Test
    void criar_deveRejeitar_quandoValorForNegativo() {
        var valor = new BigDecimal("-0.01");
        var moeda = Currency.getInstance("BRL");

        assertThatThrownBy(() -> Transacao.criar(valor, moeda))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("valor deve ser maior que zero");
    }

    @Test
    void criar_deveRejeitar_quandoValorForNulo() {
        var moeda = Currency.getInstance("BRL");

        assertThatThrownBy(() -> Transacao.criar(null, moeda))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("valor deve ser informado");
    }
}
