package br.com.credpay.transacoes.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class TransacaoTest {

    private static final UUID ID = UUID.fromString("7b8b61c2-9f63-4d74-9f8d-89cb52de0ed9");

    @ParameterizedTest
    @ValueSource(strings = {"APROVADA", "REJEITADA"})
    void concluir_deveAplicarResultadoPreservandoDadosEOriginal(String resultado) {
        var valor = new BigDecimal("123.450");
        var moeda = Currency.getInstance("BRL");
        var pendente = Transacao.criar(ID, valor, moeda);

        var concluida = pendente.concluir(StatusTransacao.valueOf(resultado));

        assertThat(concluida.status().name()).isEqualTo(resultado);
        assertThat(concluida.id()).isEqualTo(ID);
        assertThat(concluida.valor()).isEqualTo(valor);
        assertThat(concluida.valor().scale()).isEqualTo(3);
        assertThat(concluida.moeda()).isEqualTo(moeda);
        assertThat(pendente.status()).isEqualTo(StatusTransacao.PENDENTE);
    }

    @ParameterizedTest
    @CsvSource({
            "APROVADA, APROVADA", "APROVADA, REJEITADA", "APROVADA, PENDENTE",
            "REJEITADA, APROVADA", "REJEITADA, REJEITADA", "REJEITADA, PENDENTE"
    })
    void concluir_deveRecusarNovaTransicao_quandoEstadoForFinal(StatusTransacao original, StatusTransacao novo) {
        var finalizada = Transacao.criar(ID, new BigDecimal("10.00"), Currency.getInstance("BRL"))
                .concluir(original);

        assertThatThrownBy(() -> finalizada.concluir(novo))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("transação já concluída");
        assertThat(finalizada.status()).isEqualTo(original);
    }

    @ParameterizedTest
    @NullSource
    @EnumSource(value = StatusTransacao.class, names = "PENDENTE")
    void concluir_deveRecusarResultadoSemEstadoFinal(StatusTransacao resultado) {
        var pendente = Transacao.criar(ID, new BigDecimal("10.00"), Currency.getInstance("BRL"));

        assertThatThrownBy(() -> pendente.concluir(resultado))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("resultado deve ser APROVADA ou REJEITADA");
        assertThat(pendente.status()).isEqualTo(StatusTransacao.PENDENTE);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "7b8b61c2-9f63-4d74-9f8d-89cb52de0ed9",
            "fb72faab-a904-4aab-9bc1-2a93da8de941"
    })
    void criar_devePreservarId_quandoTransacaoForValida(String identidade) {
        var id = UUID.fromString(identidade);
        var valor = new BigDecimal("10.00");
        var moeda = Currency.getInstance("BRL");

        var transacao = Transacao.criar(id, valor, moeda);

        assertThat(transacao.id()).isEqualTo(id);
        assertThat(transacao.valor()).isEqualTo(valor);
        assertThat(transacao.moeda()).isEqualTo(moeda);
        assertThat(transacao.status()).isEqualTo(StatusTransacao.PENDENTE);
    }

    @ParameterizedTest
    @CsvSource({"10.00, BRL", "123.456, USD"})
    void criar_devePreservarValorEMoeda_quandoTransacaoForValida(String quantia, String codigoMoeda) {
        var valor = new BigDecimal(quantia);
        var moeda = Currency.getInstance(codigoMoeda);

        var transacao = Transacao.criar(ID, valor, moeda);

        assertThat(transacao.valor()).isEqualByComparingTo(valor);
        assertThat(transacao.valor().scale()).isEqualTo(valor.scale());
        assertThat(transacao.moeda()).isEqualTo(moeda);
        assertThat(transacao.status()).isEqualTo(StatusTransacao.PENDENTE);
    }

    @Test
    void criar_deveRejeitar_quandoIdForNulo() {
        var valor = new BigDecimal("10.00");
        var moeda = Currency.getInstance("BRL");

        assertThatThrownBy(() -> Transacao.criar(null, valor, moeda))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("id deve ser informado");
    }

    @Test
    void criar_deveDefinirStatusPendente_quandoTransacaoForValida() {
        var valor = new BigDecimal("10.00");
        var moeda = Currency.getInstance("BRL");

        var transacao = Transacao.criar(ID, valor, moeda);

        assertThat(transacao.status()).isEqualTo(StatusTransacao.PENDENTE);
    }

    @Test
    void criar_deveRejeitar_quandoValorForZero() {
        var moeda = Currency.getInstance("BRL");

        assertThatThrownBy(() -> Transacao.criar(ID, BigDecimal.ZERO, moeda))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("valor deve ser maior que zero");
    }

    @Test
    void criar_deveRejeitar_quandoValorForNegativo() {
        var valor = new BigDecimal("-0.01");
        var moeda = Currency.getInstance("BRL");

        assertThatThrownBy(() -> Transacao.criar(ID, valor, moeda))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("valor deve ser maior que zero");
    }

    @Test
    void criar_deveRejeitar_quandoValorForNulo() {
        var moeda = Currency.getInstance("BRL");

        assertThatThrownBy(() -> Transacao.criar(ID, null, moeda))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("valor deve ser informado");
    }

    @Test
    void criar_deveRejeitar_quandoMoedaForNula() {
        var valor = new BigDecimal("10.00");

        assertThatThrownBy(() -> Transacao.criar(ID, valor, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("moeda deve ser informada");
    }
}
