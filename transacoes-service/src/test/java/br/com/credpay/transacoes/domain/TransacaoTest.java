package br.com.credpay.transacoes.domain;

import static org.assertj.core.api.Assertions.assertThat;

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
}
