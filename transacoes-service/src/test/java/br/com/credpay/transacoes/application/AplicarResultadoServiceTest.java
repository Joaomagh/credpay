package br.com.credpay.transacoes.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

import br.com.credpay.transacoes.domain.StatusTransacao;
import br.com.credpay.transacoes.domain.Transacao;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class AplicarResultadoServiceTest {

    private static final Instant EVENTO_EM = Instant.parse("2026-10-03T12:00:00.123456789Z");
    private static final Instant APLICADO_EM = Instant.parse("2026-10-03T12:00:01.987654321Z");

    @ParameterizedTest
    @EnumSource(value = StatusTransacao.class, names = {"APROVADA", "REJEITADA"})
    void executar_deveRegistrarPrimeiraTransicaoComInstanteLocal(StatusTransacao estado) {
        var transacoes = mock(TransacaoRepository.class);
        var historico = mock(TransicaoRepository.class);
        var entrada = entrada(estado);
        var original = Transacao.criar(entrada.transactionId(), new BigDecimal("123.450"), Currency.getInstance("BRL"));
        when(transacoes.buscarPorId(entrada.transactionId())).thenReturn(Optional.of(original));
        when(historico.existeCriacao(entrada.causationId(), entrada.transactionId())).thenReturn(true);
        var service = new AplicarResultadoService(transacoes, historico, Clock.fixed(APLICADO_EM, ZoneOffset.UTC));

        var resultado = service.executar(entrada);

        assertThat(resultado).isEqualTo(new TransicaoRecebida(entrada.eventId(), entrada.transactionId(),
                "TransacaoProcessada", 1, entrada.correlationId(), entrada.causationId(), StatusTransacao.PENDENTE,
                estado, "processamento-service/TransacaoProcessada.v1", EVENTO_EM,
                Instant.parse("2026-10-03T12:00:01.987654Z")));
        verify(historico).registrar(resultado);
        assertThat(original.status()).isEqualTo(StatusTransacao.PENDENTE);
        assertThat(original.valor()).isEqualTo(new BigDecimal("123.450"));
    }

    private TransacaoProcessadaRecebida entrada(StatusTransacao estado) {
        var id = UUID.randomUUID();
        return new TransacaoProcessadaRecebida(UUID.randomUUID(), id, EVENTO_EM, id, UUID.randomUUID(), estado);
    }

    @ParameterizedTest
    @EnumSource(value = StatusTransacao.class, names = {"APROVADA", "REJEITADA"})
    void executar_deveRetornarOriginalSemRelogioOuEscrita_quandoReplayForEquivalente(StatusTransacao estado) {
        var transacoes = mock(TransacaoRepository.class);
        var historico = mock(TransicaoRepository.class);
        var clock = mock(Clock.class);
        var entrada = entrada(estado);
        var original = new TransicaoRecebida(entrada.eventId(), entrada.transactionId(), "TransacaoProcessada", 1,
                entrada.correlationId(), entrada.causationId(), StatusTransacao.PENDENTE, estado,
                "processamento-service/TransacaoProcessada.v1", EVENTO_EM,
                Instant.parse("2026-10-03T12:00:01.987654Z"));
        when(historico.buscarPorEvento(entrada.eventId())).thenReturn(Optional.of(original));
        var service = new AplicarResultadoService(transacoes, historico, clock);

        var resultado = service.executar(entrada);

        assertThat(resultado).isSameAs(original);
        verifyNoInteractions(transacoes, clock);
        verify(historico, never()).registrar(any());
        verify(historico, never()).existeCriacao(any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"transacao", "causa", "instante", "status"})
    void executar_deveRecusarDivergenciaDoMesmoEventoSemSobrescrita(String campo) {
        var transacoes = mock(TransacaoRepository.class);
        var historico = mock(TransicaoRepository.class);
        var clock = mock(Clock.class);
        var original = entrada(StatusTransacao.APROVADA);
        var registro = new TransicaoRecebida(original.eventId(), original.transactionId(), "TransacaoProcessada", 1,
                original.correlationId(), original.causationId(), StatusTransacao.PENDENTE, original.status(),
                "processamento-service/TransacaoProcessada.v1", EVENTO_EM, APLICADO_EM);
        var outraTransacao = UUID.randomUUID();
        var divergente = new TransacaoProcessadaRecebida(original.eventId(),
                campo.equals("transacao") ? outraTransacao : original.transactionId(),
                campo.equals("instante") ? EVENTO_EM.plusNanos(1) : EVENTO_EM,
                campo.equals("transacao") ? outraTransacao : original.correlationId(),
                campo.equals("causa") ? UUID.randomUUID() : original.causationId(),
                campo.equals("status") ? StatusTransacao.REJEITADA : original.status());
        when(historico.buscarPorEvento(original.eventId())).thenReturn(Optional.of(registro));
        var service = new AplicarResultadoService(transacoes, historico, clock);

        assertThatThrownBy(() -> service.executar(divergente))
                .isInstanceOf(ConflitoResultadoException.class);

        verifyNoInteractions(transacoes, clock);
        verify(historico, never()).registrar(any());
    }

    @ParameterizedTest
    @EnumSource(value = StatusTransacao.class, names = {"APROVADA", "REJEITADA"})
    void executar_deveRecusarNovoEventoParaEstadoFinal(StatusTransacao estado) {
        var transacoes = mock(TransacaoRepository.class);
        var historico = mock(TransicaoRepository.class);
        var clock = mock(Clock.class);
        var entrada = entrada(StatusTransacao.APROVADA);
        var original = Transacao.criar(entrada.transactionId(), new BigDecimal("123.450"), Currency.getInstance("BRL"))
                .concluir(estado);
        when(transacoes.buscarPorId(entrada.transactionId())).thenReturn(Optional.of(original));
        when(historico.existeCriacao(entrada.causationId(), entrada.transactionId())).thenReturn(true);
        var service = new AplicarResultadoService(transacoes, historico, clock);

        assertThatThrownBy(() -> service.executar(entrada))
                .isInstanceOf(ConflitoResultadoException.class);

        assertThat(original.status()).isEqualTo(estado);
        verifyNoInteractions(clock);
        verify(historico, never()).registrar(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"transacao", "causa"})
    void executar_deveRecusarSemEscritaOuRelogio_quandoTransacaoOuCausaNaoExistir(String ausente) {
        var transacoes = mock(TransacaoRepository.class);
        var historico = mock(TransicaoRepository.class);
        var clock = mock(Clock.class);
        var entrada = entrada(StatusTransacao.APROVADA);
        if (ausente.equals("causa")) {
            when(transacoes.buscarPorId(entrada.transactionId())).thenReturn(Optional.of(
                    Transacao.criar(entrada.transactionId(), new BigDecimal("123.450"), Currency.getInstance("BRL"))));
        }
        var service = new AplicarResultadoService(transacoes, historico, clock);

        assertThatThrownBy(() -> service.executar(entrada)).isInstanceOf(TransicaoRecusadaException.class);

        verifyNoInteractions(clock);
        verify(historico, never()).registrar(any());
    }
}
