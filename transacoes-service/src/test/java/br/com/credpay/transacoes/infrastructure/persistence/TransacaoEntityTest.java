package br.com.credpay.transacoes.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.credpay.transacoes.domain.StatusTransacao;
import br.com.credpay.transacoes.domain.Transacao;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TransacaoEntityTest {

    @ParameterizedTest
    @EnumSource(StatusTransacao.class)
    void paraDominio_devePreservarEstadoEDadosSemReiniciarTransacao(StatusTransacao status) {
        var id = UUID.fromString("7b8b61c2-9f63-4d74-9f8d-89cb52de0ed9");
        var valor = new BigDecimal("123.450");
        var moeda = Currency.getInstance("BRL");
        var transacao = Transacao.criar(id, valor, moeda);
        if (status != StatusTransacao.PENDENTE) {
            transacao = transacao.concluir(status);
        }

        var reconstruida = new TransacaoEntity(transacao).paraDominio();

        assertThat(reconstruida.id()).isEqualTo(id);
        assertThat(reconstruida.valor()).isEqualTo(valor);
        assertThat(reconstruida.valor().scale()).isEqualTo(3);
        assertThat(reconstruida.moeda()).isEqualTo(moeda);
        assertThat(reconstruida.status()).isEqualTo(status);
    }
}
