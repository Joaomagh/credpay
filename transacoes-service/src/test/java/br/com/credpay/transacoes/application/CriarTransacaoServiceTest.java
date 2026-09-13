package br.com.credpay.transacoes.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Currency;

import br.com.credpay.transacoes.domain.StatusTransacao;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CriarTransacaoServiceTest {

    @ParameterizedTest
    @CsvSource({"10.00, BRL", "123.456, USD"})
    void executar_devePreservarDadosValidados_quandoTransacaoForValida(String quantia, String codigoMoeda) {
        var service = new CriarTransacaoService();
        var valor = new BigDecimal(quantia);
        var moeda = Currency.getInstance(codigoMoeda);

        var resultado = service.executar(valor, moeda);

        assertThat(resultado.id()).isNotNull();
        assertThat(resultado.valor()).isEqualByComparingTo(valor);
        assertThat(resultado.valor().scale()).isEqualTo(valor.scale());
        assertThat(resultado.moeda()).isEqualTo(moeda);
        assertThat(resultado.status()).isEqualTo(StatusTransacao.PENDENTE);
    }
}
