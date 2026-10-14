package br.com.credpay.transacoes.application;

import java.time.Clock;
import java.util.UUID;

import br.com.credpay.transacoes.domain.StatusTransacao;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

public class SolicitarEncerramentoService {

    private final TransicaoRepository transicoes;
    private final TransacaoRepository transacoes;
    private final PedidoEncerramentoRepository pedidos;
    private final OutboxPedidoEncerramentoRepository outbox;
    private final Clock clock;
    private final ObjectMapper mapper;

    public SolicitarEncerramentoService(TransicaoRepository transicoes, TransacaoRepository transacoes,
            PedidoEncerramentoRepository pedidos,
            OutboxPedidoEncerramentoRepository outbox, Clock clock, ObjectMapper mapper) {
        this.transicoes = transicoes;
        this.transacoes = transacoes;
        this.pedidos = pedidos;
        this.outbox = outbox;
        this.clock = clock;
        this.mapper = mapper;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PedidoEncerramentoRegistrado executar(UUID pedidoId, UUID transactionId, UUID evidenceRef) {
        transicoes.bloquearIdentidades(pedidoId, transactionId);
        var existente = pedidos.buscarPorPedidoId(pedidoId);
        if (existente.isPresent()) {
            var original = existente.orElseThrow();
            if (!original.transactionId().equals(transactionId) || !original.evidenceRef().equals(evidenceRef)) {
                throw new PedidoEncerramentoRecusadoException("pedido de encerramento divergente");
            }
            return original;
        }
        if (pedidos.existePorTransactionId(transactionId)) {
            throw new PedidoEncerramentoRecusadoException("pedido de encerramento divergente");
        }
        var transacao = transacoes.buscarPorId(transactionId)
                .orElseThrow(() -> new PedidoEncerramentoRecusadoException("transação inexistente ou não pendente"));
        if (transacao.status() != StatusTransacao.PENDENTE) {
            throw new PedidoEncerramentoRecusadoException("transação inexistente ou não pendente");
        }
        var criacoes = pedidos.buscarCriacoesOriginais(transactionId);
        if (criacoes.size() != 1) {
            throw new PedidoEncerramentoRecusadoException("criação original ausente ou ambígua");
        }
        var criacao = criacoes.getFirst();
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
