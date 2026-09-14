package br.com.credpay.transacoes.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

import br.com.credpay.transacoes.domain.StatusTransacao;
import br.com.credpay.transacoes.domain.Transacao;
import org.junit.jupiter.api.Test;

class BuscarTransacaoServiceTest {

    private static final UUID TRANSACAO_ID = UUID.fromString("7b8b61c2-9f63-4d74-9f8d-89cb52de0ed9");

    @Test
    void executar_deveRetornarDadosPersistidos_quandoTransacaoExistir() {
        var repository = mock(TransacaoRepository.class);
        var service = new BuscarTransacaoService(repository);
        var transacao = Transacao.criar(
                TRANSACAO_ID, new BigDecimal("10.00"), Currency.getInstance("BRL"));
        when(repository.buscarPorId(TRANSACAO_ID)).thenReturn(Optional.of(transacao));

        var resultado = service.executar(TRANSACAO_ID);

        assertThat(resultado.id()).isEqualTo(TRANSACAO_ID);
        assertThat(resultado.valor()).isEqualByComparingTo("10.00");
        assertThat(resultado.valor().scale()).isEqualTo(2);
        assertThat(resultado.moeda()).isEqualTo(Currency.getInstance("BRL"));
        assertThat(resultado.status()).isEqualTo(StatusTransacao.PENDENTE);
        verify(repository).buscarPorId(TRANSACAO_ID);
    }

    @Test
    void executar_deveFalhar_quandoTransacaoNaoExistir() {
        var repository = mock(TransacaoRepository.class);
        var service = new BuscarTransacaoService(repository);
        when(repository.buscarPorId(TRANSACAO_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.executar(TRANSACAO_ID))
                .isInstanceOf(TransacaoNaoEncontradaException.class)
                .hasMessage("transação não encontrada");
        verify(repository).buscarPorId(TRANSACAO_ID);
    }
}
