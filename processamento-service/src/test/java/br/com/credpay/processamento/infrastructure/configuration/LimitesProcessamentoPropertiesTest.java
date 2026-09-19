package br.com.credpay.processamento.infrastructure.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import br.com.credpay.processamento.ProcessamentoServiceApplication;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;

class LimitesProcessamentoPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ProcessamentoServiceApplication.class);

    @Test
    void configuracao_devePreservarLimitesIndependentes_quandoMoedasForemConfiguradas() {
        contextRunner.withPropertyValues(
                        "credpay.processamento.limites.brl=100.00",
                        "credpay.processamento.limites.USD=20.123")
                .run(contexto -> {
                    assertThat(contexto).hasNotFailed();
                    var limites = contexto.getBean(LimitesProcessamentoProperties.class);
                    assertThat(limites.limitePara(Currency.getInstance("BRL")))
                            .isEqualTo("100.00");
                    assertThat(limites.limitePara(Currency.getInstance("USD")))
                            .isEqualTo("20.123");
                });
    }

    @Test
    void configuracao_deveFalhar_quandoLimitesNaoForemInformados() {
        contextRunner.run(contexto -> {
            assertThat(contexto).hasFailed();
            assertThat(contexto.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasStackTraceContaining("ao menos um limite por moeda deve ser informado");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-0.01"})
    void configuracao_deveFalhar_quandoAlgumLimiteNaoForPositivo(String limite) {
        contextRunner.withPropertyValues(
                        "credpay.processamento.limites.BRL=100.00",
                        "credpay.processamento.limites.USD=" + limite)
                .run(contexto -> {
                    assertThat(contexto).hasFailed();
                    assertThat(contexto.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasStackTraceContaining("limite deve ser maior que zero");
                });
    }

    @Test
    void configuracao_deveFalhar_quandoCodigoDeMoedaForInvalido() {
        contextRunner.withPropertyValues("credpay.processamento.limites.INVALID=100")
                .run(contexto -> {
                    assertThat(contexto).hasFailed();
                    assertThat(contexto.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalArgumentException.class)
                            .hasStackTraceContaining("moeda deve ser um codigo ISO 4217 valido");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", ""})
    void configuracao_deveFalhar_quandoLimiteNaoForNumero(String limite) {
        contextRunner.withPropertyValues("credpay.processamento.limites.BRL=" + limite)
                .run(contexto -> assertThat(contexto).hasFailed());
    }

    @Test
    void configuracao_deveLerLimite_quandoFornecidoPorVariavelDeAmbiente() {
        contextRunner.withInitializer(contexto -> contexto.getEnvironment().getPropertySources()
                        .addFirst(new SystemEnvironmentPropertySource("test-systemEnvironment",
                                Map.of("CREDPAY_PROCESSAMENTO_LIMITES_BRL", "100.00"))))
                .run(contexto -> {
                    assertThat(contexto).hasNotFailed();
                    assertThat(contexto.getBean(LimitesProcessamentoProperties.class)
                            .limitePara(Currency.getInstance("BRL"))).isEqualTo("100.00");
                });
    }

    @Test
    void limitePara_deveFalhar_quandoMoedaNaoTiverPolitica() {
        var limites = new LimitesProcessamentoProperties(Map.of("BRL", new BigDecimal("100")));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> limites.limitePara(Currency.getInstance("USD")))
                .withMessage("limite nao configurado para moeda USD");
    }

    @Test
    void limitePara_deveFalhar_quandoMoedaForNula() {
        var limites = new LimitesProcessamentoProperties(Map.of("BRL", new BigDecimal("100")));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> limites.limitePara(null))
                .withMessage("moeda deve ser informada");
    }

    @Test
    void configuracao_deveFalhar_quandoChavesColidiremAposNormalizacao() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new LimitesProcessamentoProperties(Map.of(
                        "BRL", new BigDecimal("100"), "brl", new BigDecimal("200"))))
                .withMessage("limite duplicado para moeda BRL");
    }

}
