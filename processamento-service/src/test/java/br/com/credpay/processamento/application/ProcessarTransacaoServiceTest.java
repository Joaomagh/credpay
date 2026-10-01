package br.com.credpay.processamento.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import br.com.credpay.processamento.domain.StatusProcessamento;
import br.com.credpay.processamento.infrastructure.configuration.LimitesProcessamentoProperties;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
class ProcessarTransacaoServiceTest {

    private final ProcessarTransacaoService service = new ProcessarTransacaoService(
            new LimitesProcessamentoProperties(Map.of(
                    "BRL", new BigDecimal("100.00"),
                    "USD", new BigDecimal("20.00"))));

    @ParameterizedTest
    @CsvSource({
            "99.99, BRL, APROVADA",
            "100.00, BRL, APROVADA",
            "100.01, BRL, REJEITADA",
            "100.00, USD, REJEITADA"
    })
    void executar_deveDecidirPeloLimiteDaMoeda_quandoTransacaoForValida(
            String valor, String moeda, StatusProcessamento esperado) {
        assertThat(service.executar(new BigDecimal(valor), Currency.getInstance(moeda)).status())
                .isEqualTo(esperado);
    }

    @Test
    void executar_devePreservarSnapshot_quandoPoliticaMudarDepoisDaDecisao() {
        var limite = new AtomicReference<>(new BigDecimal("100.00"));
        var service = new ProcessarTransacaoService(moeda -> limite.get());
        var valor = new BigDecimal("100.00");
        var moeda = Currency.getInstance("BRL");

        var anterior = service.executar(valor, moeda);
        limite.set(new BigDecimal("50.00"));
        var posterior = service.executar(valor, moeda);

        assertThat(anterior.valor()).isEqualTo(new BigDecimal("100.00"));
        assertThat(anterior.moeda()).isEqualTo(moeda);
        assertThat(anterior.limiteAplicado()).isEqualTo(new BigDecimal("100.00"));
        assertThat(anterior.status()).isEqualTo(StatusProcessamento.APROVADA);
        assertThat(posterior.valor()).isEqualTo(valor);
        assertThat(posterior.moeda()).isEqualTo(moeda);
        assertThat(posterior.limiteAplicado()).isEqualTo(new BigDecimal("50.00"));
        assertThat(posterior.status()).isEqualTo(StatusProcessamento.REJEITADA);
    }

    @Test
    void executar_deveDecidirERegistrarMesmoLimite_quandoFonteAlternarEntreConsultas() {
        var consultas = new AtomicInteger();
        var service = new ProcessarTransacaoService(moeda -> consultas.incrementAndGet() == 1
                ? new BigDecimal("100.00") : new BigDecimal("50.00"));

        var resultado = service.executar(new BigDecimal("75.00"), Currency.getInstance("BRL"));

        assertThat(resultado.status()).isEqualTo(StatusProcessamento.APROVADA);
        assertThat(resultado.limiteAplicado()).isEqualTo(new BigDecimal("100.00"));
        assertThat(consultas.get()).isEqualTo(1);
    }

    @Test
    void executar_deveFalharSemDecidir_quandoMoedaNaoTiverLimite() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.executar(new BigDecimal("10.00"), Currency.getInstance("EUR")))
                .withMessage("limite nao configurado para moeda EUR");
    }

    @Test
    void executar_deveFalharSemDecidir_quandoMoedaForNula() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.executar(new BigDecimal("10.00"), null))
                .withMessage("moeda deve ser informada");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "-0.01"})
    void executar_devePreservarValidacaoDoDominio_quandoValorForInvalido(String valor) {
        var quantia = valor == null ? null : new BigDecimal(valor);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.executar(quantia, Currency.getInstance("BRL")))
                .withMessage(valor == null ? "valor deve ser informado" : "valor deve ser maior que zero");
    }
}
