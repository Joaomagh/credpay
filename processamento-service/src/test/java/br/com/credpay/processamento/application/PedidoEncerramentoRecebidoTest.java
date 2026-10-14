package br.com.credpay.processamento.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class PedidoEncerramentoRecebidoTest {

    private final UUID pedido = UUID.randomUUID();
    private final UUID transacao = UUID.randomUUID();
    private final UUID criacao = UUID.randomUUID();
    private final UUID evidencia = UUID.randomUUID();
    private final Instant instante = Instant.parse("2026-10-14T12:00:00.123456789Z");

    @Test
    void pedido_devePreservarIdentidadesEPrecisaoDoCenarioControlado() {
        var recebido = pedido(transacao);

        assertThat(recebido.eventId()).isEqualTo(pedido);
        assertThat(recebido.transactionId()).isEqualTo(transacao);
        assertThat(recebido.creationEventId()).isEqualTo(criacao);
        assertThat(recebido.occurredAt()).isEqualTo(instante);
        assertThat(recebido.evidenceRef()).isEqualTo(evidencia);
        assertThat(recebido.motivo()).isEqualTo(
                PedidoEncerramentoRecebido.Motivo.FALHA_PERMANENTE_CONTROLADA);
    }

    @Test
    void pedido_deveRejeitarCorrelacaoDeOutraTransacao() {
        assertThatThrownBy(() -> pedido(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("correlationId deve corresponder a transactionId");
    }

    @ParameterizedTest
    @EnumSource(CampoAusente.class)
    void pedido_deveRejeitarCampoObrigatorioAusente(CampoAusente campo) {
        assertThatThrownBy(() -> new PedidoEncerramentoRecebido(
                campo == CampoAusente.eventId ? null : pedido,
                campo == CampoAusente.transactionId ? null : transacao,
                campo == CampoAusente.creationEventId ? null : criacao,
                campo == CampoAusente.occurredAt ? null : instante,
                campo == CampoAusente.correlationId ? null : transacao,
                campo == CampoAusente.motivo ? null
                        : PedidoEncerramentoRecebido.Motivo.FALHA_PERMANENTE_CONTROLADA,
                campo == CampoAusente.evidenceRef ? null : evidencia))
                .isInstanceOf(NullPointerException.class).hasMessage(campo.name());
    }

    private enum CampoAusente {
        eventId, transactionId, creationEventId, occurredAt, correlationId, motivo, evidenceRef
    }

    private PedidoEncerramentoRecebido pedido(UUID correlacao) {
        return new PedidoEncerramentoRecebido(pedido, transacao, criacao, instante,
                correlacao, PedidoEncerramentoRecebido.Motivo.FALHA_PERMANENTE_CONTROLADA,
                evidencia);
    }
}
