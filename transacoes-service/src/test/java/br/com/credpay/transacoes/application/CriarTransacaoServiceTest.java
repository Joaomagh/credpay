package br.com.credpay.transacoes.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Currency;

import br.com.credpay.transacoes.domain.StatusTransacao;
import org.junit.jupiter.api.Test;

class CriarTransacaoServiceTest {

    @Test
    void deveCriarTransacaoPendenteSemPersistencia() {
        var service = new CriarTransacaoService();
        var valor = new BigDecimal("10.00");
        var moeda = Currency.getInstance("BRL");

        var resultado = service.executar(valor, moeda);

        assertThat(resultado.id()).isNotNull();
        assertThat(resultado.valor()).isEqualByComparingTo(valor);
        assertThat(resultado.moeda()).isEqualTo(moeda);
        assertThat(resultado.status()).isEqualTo(StatusTransacao.PENDENTE);
    }
}
