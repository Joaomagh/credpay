package br.com.credpay.transacoes.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class RespostaEncerramentoRecebidaTest {

    private final UUID eventId = UUID.randomUUID();
    private final UUID transactionId = UUID.randomUUID();
    private final UUID creationEventId = UUID.randomUUID();
    private final UUID pedidoId = UUID.randomUUID();
    private final Instant instante = Instant.parse("2026-10-14T18:00:00.123456789Z");

    @ParameterizedTest
    @EnumSource(RespostaEncerramentoRecebida.Resultado.class)
    void construir_devePreservarSemanticaEPrecisao(RespostaEncerramentoRecebida.Resultado resultado) {
        var output = resultado == RespostaEncerramentoRecebida.Resultado.DECISAO_EXISTENTE
                ? Optional.of(UUID.randomUUID()) : Optional.<UUID>empty();

        var resposta = new RespostaEncerramentoRecebida(eventId, transactionId, creationEventId, instante,
                transactionId, pedidoId, resultado, output);

        assertThat(resposta).isEqualTo(new RespostaEncerramentoRecebida(eventId, transactionId, creationEventId,
                Instant.parse("2026-10-14T21:00:00.123456789+03:00"), transactionId, pedidoId, resultado, output));
        assertThat(resposta.occurredAt().getNano()).isEqualTo(123456789);
        assertThat(resposta.outputEventId()).isEqualTo(output);
    }

    @ParameterizedTest
    @ValueSource(strings = {"evento", "transacao", "criacao", "instante", "correlacao", "causa", "resultado", "saida"})
    void construir_deveRecusarCampoObrigatorioAusente(String campo) {
        assertThatThrownBy(() -> new RespostaEncerramentoRecebida(
                campo.equals("evento") ? null : eventId,
                campo.equals("transacao") ? null : transactionId,
                campo.equals("criacao") ? null : creationEventId,
                campo.equals("instante") ? null : instante,
                campo.equals("correlacao") ? null : transactionId,
                campo.equals("causa") ? null : pedidoId,
                campo.equals("resultado") ? null : RespostaEncerramentoRecebida.Resultado.CONFIRMADO,
                campo.equals("saida") ? null : Optional.empty()))
                .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @EnumSource(RespostaEncerramentoRecebida.Resultado.class)
    void construir_deveExigirSaidaSomenteNaNegativa(RespostaEncerramentoRecebida.Resultado resultado) {
        var output = resultado == RespostaEncerramentoRecebida.Resultado.CONFIRMADO
                ? Optional.of(UUID.randomUUID()) : Optional.<UUID>empty();

        assertThatThrownBy(() -> new RespostaEncerramentoRecebida(eventId, transactionId, creationEventId,
                instante, transactionId, pedidoId, resultado, output))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("outputEventId deve existir somente em DECISAO_EXISTENTE");
    }

    @Test
    void construir_deveRecusarCorrelacaoDiferenteDaTransacao() {
        assertThatThrownBy(() -> new RespostaEncerramentoRecebida(eventId, transactionId, creationEventId,
                instante, UUID.randomUUID(), pedidoId, RespostaEncerramentoRecebida.Resultado.CONFIRMADO,
                Optional.empty())).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("correlationId deve corresponder a transactionId");
    }
}
