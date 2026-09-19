package br.com.credpay.processamento.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
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

    @Test
    void processar_deveRejeitar_quandoValorForMaiorQueLimite() {
        var resultado = ProcessadorTransacao.processar(
                new BigDecimal("100.01"),
                new BigDecimal("100.00"));

        assertThat(resultado).isEqualTo(StatusProcessamento.REJEITADA);
    }

    @Test
    void processar_deveFalhar_quandoValorForNulo() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ProcessadorTransacao.processar(
                        null,
                        new BigDecimal("100.00")))
                .withMessage("valor deve ser informado");
    }
}
