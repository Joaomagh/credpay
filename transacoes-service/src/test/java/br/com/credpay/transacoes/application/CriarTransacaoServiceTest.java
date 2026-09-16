package br.com.credpay.transacoes.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

import br.com.credpay.transacoes.domain.StatusTransacao;
import br.com.credpay.transacoes.domain.Transacao;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

class CriarTransacaoServiceTest {

    private static final UUID CHAVE_IDEMPOTENCIA =
            UUID.fromString("03714dde-d152-47f0-97f0-82171ffbe150");

    @ParameterizedTest
    @CsvSource({"10.00, BRL", "123.456, USD"})
    void executar_devePersistirComChave_quandoForPrimeiraCriacao(String quantia, String codigoMoeda) {
        var repository = mock(TransacaoRepository.class);
        var service = new CriarTransacaoService(repository);
        var valor = new BigDecimal(quantia);
        var moeda = Currency.getInstance(codigoMoeda);
        when(repository.buscarPorChaveIdempotencia(CHAVE_IDEMPOTENCIA)).thenReturn(Optional.empty());

        var resultado = service.executar(CHAVE_IDEMPOTENCIA, valor, moeda);

        verify(repository).bloquearChaveIdempotencia(CHAVE_IDEMPOTENCIA);
        var transacaoPersistida = ArgumentCaptor.forClass(Transacao.class);
        verify(repository).inserir(eq(CHAVE_IDEMPOTENCIA), transacaoPersistida.capture());
        assertThat(resultado.id()).isEqualTo(transacaoPersistida.getValue().id());
        assertThat(resultado.valor()).isEqualByComparingTo(valor);
        assertThat(resultado.valor().scale()).isEqualTo(valor.scale());
        assertThat(resultado.moeda()).isEqualTo(moeda);
        assertThat(resultado.status()).isEqualTo(StatusTransacao.PENDENTE);
    }

    @ParameterizedTest
    @CsvSource({"10, BRL", "10.000, BRL"})
    void executar_deveReutilizarOriginal_quandoChaveEPayloadForemEquivalentes(String quantia, String codigoMoeda) {
        var repository = mock(TransacaoRepository.class);
        var service = new CriarTransacaoService(repository);
        var original = Transacao.criar(
                UUID.fromString("44fe8ae7-47a6-45f2-bf6c-46a83ee782ee"),
                new BigDecimal("10.00"),
                Currency.getInstance("BRL"));
        when(repository.buscarPorChaveIdempotencia(CHAVE_IDEMPOTENCIA))
                .thenReturn(Optional.of(original));

        var resultado = service.executar(
                CHAVE_IDEMPOTENCIA,
                new BigDecimal(quantia),
                Currency.getInstance(codigoMoeda));

        assertThat(resultado.id()).isEqualTo(original.id());
        assertThat(resultado.valor()).isEqualTo(original.valor());
        assertThat(resultado.valor().scale()).isEqualTo(2);
        assertThat(resultado.moeda()).isEqualTo(original.moeda());
        assertThat(resultado.status()).isEqualTo(original.status());
        verify(repository, never()).inserir(eq(CHAVE_IDEMPOTENCIA), any(Transacao.class));
    }

    @ParameterizedTest
    @CsvSource({"20.00, BRL", "10.00, USD"})
    void executar_deveFalhar_quandoChaveForReutilizadaComPayloadDiferente(
            String quantia, String codigoMoeda) {
        var repository = mock(TransacaoRepository.class);
        var service = new CriarTransacaoService(repository);
        var original = Transacao.criar(
                UUID.fromString("44fe8ae7-47a6-45f2-bf6c-46a83ee782ee"),
                new BigDecimal("10.00"),
                Currency.getInstance("BRL"));
        when(repository.buscarPorChaveIdempotencia(CHAVE_IDEMPOTENCIA))
                .thenReturn(Optional.of(original));

        assertThatThrownBy(() -> service.executar(
                CHAVE_IDEMPOTENCIA,
                new BigDecimal(quantia),
                Currency.getInstance(codigoMoeda)))
                .isInstanceOf(ConflitoIdempotenciaException.class)
                .hasMessage("chave de idempotência já utilizada com outro payload");
        verify(repository, never()).inserir(eq(CHAVE_IDEMPOTENCIA), any(Transacao.class));
    }
}
