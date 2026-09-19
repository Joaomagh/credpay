package br.com.credpay.processamento.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProcessadorTransacaoTest {

    @ParameterizedTest
    @ValueSource(strings = {"99.99", "100.00"})
    void processar_deveAprovar_quandoValorForMenorOuIgualAoLimite(String valor) {
        var resultado = ProcessadorTransacao.processar(
                new BigDecimal(valor),
                new BigDecimal("100.00"));

        assertThat(resultado).isEqualTo(StatusProcessamento.APROVADA);
    }
}
