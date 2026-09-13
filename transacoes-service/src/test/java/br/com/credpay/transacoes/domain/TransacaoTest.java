package br.com.credpay.transacoes.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TransacaoTest {

    @ParameterizedTest
    @CsvSource({"10.00, BRL", "123.456, USD"})
    void criar_devePreservarValorEMoeda_quandoTransacaoForValida(String quantia, String codigoMoeda) {
        var valor = new BigDecimal(quantia);
        var moeda = Currency.getInstance(codigoMoeda);

        var transacao = Transacao.criar(valor, moeda);

        assertThat(transacao.valor()).isEqualByComparingTo(valor);
        assertThat(transacao.valor().scale()).isEqualTo(valor.scale());
        assertThat(transacao.moeda()).isEqualTo(moeda);
        assertThat(transacao.status()).isEqualTo(StatusTransacao.PENDENTE);
    }

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

    @Test
    void criar_deveRejeitar_quandoMoedaForNula() {
        var valor = new BigDecimal("10.00");

        assertThatThrownBy(() -> Transacao.criar(valor, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("moeda deve ser informada");
    }
}
