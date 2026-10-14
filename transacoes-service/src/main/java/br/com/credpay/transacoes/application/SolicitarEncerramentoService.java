package br.com.credpay.transacoes.application;

import java.time.Clock;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

public class SolicitarEncerramentoService {

    private final TransicaoRepository transicoes;
    private final PedidoEncerramentoRepository pedidos;
    private final OutboxPedidoEncerramentoRepository outbox;
    private final Clock clock;
    private final ObjectMapper mapper;

    public SolicitarEncerramentoService(TransicaoRepository transicoes, PedidoEncerramentoRepository pedidos,
            OutboxPedidoEncerramentoRepository outbox, Clock clock, ObjectMapper mapper) {
        this.transicoes = transicoes;
        this.pedidos = pedidos;
        this.outbox = outbox;
        this.clock = clock;
        this.mapper = mapper;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PedidoEncerramentoRegistrado executar(UUID pedidoId, UUID transactionId, UUID evidenceRef) {
        transicoes.bloquearIdentidades(pedidoId, transactionId);
        var criacao = pedidos.buscarCriacoesOriginais(transactionId).getFirst();
        var pedido = new PedidoEncerramentoRegistrado(pedidoId, transactionId, criacao, clock.instant(),
                transactionId, "FALHA_PERMANENTE_CONTROLADA", evidenceRef);
        pedidos.inserir(pedido);
        try {
            var payload = new Envelope(pedido.eventId(), "EncerramentoTransacaoSolicitado", 1,
                    pedido.occurredAt().toString(), pedido.correlationId(), pedido.creationEventId(),
                    new Dados(transactionId, criacao, pedido.motivo(), evidenceRef));
            outbox.adicionar(new EventoOutbox(pedidoId, transactionId, "EncerramentoTransacaoSolicitado", 1,
                    mapper.writeValueAsString(payload), pedido.occurredAt()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("não foi possível serializar EncerramentoTransacaoSolicitado", exception);
        }
        return pedido;
    }

    private record Envelope(UUID eventId, String eventType, int eventVersion, String occurredAt,
            UUID correlationId, UUID causationId, Dados data) { }

    private record Dados(UUID transactionId, UUID creationEventId, String motivo, UUID evidenceRef) { }
}
