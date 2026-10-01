package br.com.credpay.processamento.domain;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class ResultadoProcessamentoTest {

    @Test
    void decidir_deveFalhar_quandoMoedaForNula() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ResultadoProcessamento.decidir(
                        new BigDecimal("10.00"), null, new BigDecimal("100.00")))
                .withMessage("moeda deve ser informada");
    }
}
