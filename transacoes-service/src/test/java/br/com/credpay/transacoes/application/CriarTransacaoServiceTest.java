package br.com.credpay.transacoes.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;
import java.util.Currency;

import br.com.credpay.transacoes.domain.StatusTransacao;
import br.com.credpay.transacoes.domain.Transacao;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

class CriarTransacaoServiceTest {

    @ParameterizedTest
    @CsvSource({"10.00, BRL", "123.456, USD"})
    void executar_devePersistirEPreservarDados_quandoTransacaoForValida(String quantia, String codigoMoeda) {
        var repository = mock(TransacaoRepository.class);
        var service = new CriarTransacaoService(repository);
        var valor = new BigDecimal(quantia);
        var moeda = Currency.getInstance(codigoMoeda);

        var resultado = service.executar(valor, moeda);

        assertThat(resultado.id()).isNotNull();
        assertThat(resultado.valor()).isEqualByComparingTo(valor);
        assertThat(resultado.valor().scale()).isEqualTo(valor.scale());
        assertThat(resultado.moeda()).isEqualTo(moeda);
        assertThat(resultado.status()).isEqualTo(StatusTransacao.PENDENTE);

        var transacaoPersistida = ArgumentCaptor.forClass(Transacao.class);
        verify(repository).inserir(transacaoPersistida.capture());
        assertThat(transacaoPersistida.getValue().id()).isEqualTo(resultado.id());
        assertThat(transacaoPersistida.getValue().valor()).isEqualByComparingTo(valor);
        assertThat(transacaoPersistida.getValue().valor().scale()).isEqualTo(valor.scale());
        assertThat(transacaoPersistida.getValue().moeda()).isEqualTo(moeda);
        assertThat(transacaoPersistida.getValue().status()).isEqualTo(StatusTransacao.PENDENTE);
    }
}
