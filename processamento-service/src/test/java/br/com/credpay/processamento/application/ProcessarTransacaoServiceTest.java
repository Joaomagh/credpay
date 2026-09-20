package br.com.credpay.processamento.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import br.com.credpay.processamento.ProcessamentoServiceApplication;
import br.com.credpay.processamento.domain.StatusProcessamento;
import java.math.BigDecimal;
import java.util.Currency;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ProcessarTransacaoServiceTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ProcessamentoServiceApplication.class)
            .withPropertyValues(
                    "credpay.processamento.limites.BRL=100.00",
                    "credpay.processamento.limites.USD=20.00");

    @ParameterizedTest
    @CsvSource({
            "99.99, BRL, APROVADA",
            "100.00, BRL, APROVADA",
            "100.01, BRL, REJEITADA",
            "100.00, USD, REJEITADA"
    })
    void executar_deveDecidirPeloLimiteDaMoeda_quandoTransacaoForValida(
            String valor, String moeda, StatusProcessamento esperado) {
        contextRunner.run(contexto -> {
            assertThat(contexto).hasNotFailed();
            var service = contexto.getBean(ProcessarTransacaoService.class);

            assertThat(service.executar(new BigDecimal(valor), Currency.getInstance(moeda)))
                    .isEqualTo(esperado);
        });
    }

    @Test
    void executar_deveFalharSemDecidir_quandoMoedaNaoTiverLimite() {
        contextRunner.run(contexto -> {
            var service = contexto.getBean(ProcessarTransacaoService.class);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.executar(new BigDecimal("10.00"), Currency.getInstance("EUR")))
                    .withMessage("limite nao configurado para moeda EUR");
        });
    }

    @Test
    void executar_deveFalharSemDecidir_quandoMoedaForNula() {
        contextRunner.run(contexto -> {
            var service = contexto.getBean(ProcessarTransacaoService.class);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.executar(new BigDecimal("10.00"), null))
                    .withMessage("moeda deve ser informada");
        });
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-0.01"})
    void executar_devePreservarValidacaoDoDominio_quandoValorForInvalido(String valor) {
        contextRunner.run(contexto -> {
            var service = contexto.getBean(ProcessarTransacaoService.class);
            var quantia = valor == null ? null : new BigDecimal(valor);

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> service.executar(quantia, Currency.getInstance("BRL")))
                    .withMessage(valor == null ? "valor deve ser informado" : "valor deve ser maior que zero");
        });
    }
}
