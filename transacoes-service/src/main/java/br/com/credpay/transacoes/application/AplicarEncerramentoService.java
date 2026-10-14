package br.com.credpay.transacoes.application;

import java.util.Optional;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import br.com.credpay.transacoes.domain.StatusTransacao;

import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

public class AplicarEncerramentoService {

    private final TransacaoRepository transacoes;
    private final TransicaoRepository historico;
    private final RespostaEncerramentoRepository respostas;
    private final FalhaConfirmadaRepository falhas;
    private final PedidoEncerramentoRepository pedidos;
    private final Clock clock;

    public AplicarEncerramentoService(TransacaoRepository transacoes, TransicaoRepository historico,
            RespostaEncerramentoRepository respostas, FalhaConfirmadaRepository falhas,
            PedidoEncerramentoRepository pedidos, Clock clock) {
        this.transacoes = transacoes;
        this.historico = historico;
        this.respostas = respostas;
        this.falhas = falhas;
        this.pedidos = pedidos;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AplicacaoEncerramento executar(RespostaEncerramentoRecebida resposta) {
        historico.bloquearIdentidades(resposta.eventId(), resposta.transactionId());
        var existente = respostas.buscarPorEvento(resposta.eventId());
        if (existente.isPresent()) {
            if (!existente.orElseThrow().equals(resposta)) { throw divergente(); }
        } else if (respostas.buscarPorTransactionId(resposta.transactionId()).isPresent()) { throw divergente(); }
        if (resposta.resultado() == RespostaEncerramentoRecebida.Resultado.CONFIRMADO) {
            var aplicada = historico.buscarPorEvento(resposta.eventId());
            if (aplicada.isPresent()) {
                var registro = aplicada.orElseThrow();
                if (!registro.equals(transicao(resposta, StatusTransacao.FALHOU, registro.aplicadoEm()))) {
                    throw divergente();
                }
                return new AplicacaoEncerramento(resposta, aplicada);
            }
        }
        var pedido = pedidos.buscarPorPedidoId(resposta.causationId())
                .orElseThrow(() -> new ConfirmacaoEncerramentoRecusadaException(
                        "pedido de encerramento desconhecido ou incompatível"));
        if (!pedido.transactionId().equals(resposta.transactionId())
                || !pedido.creationEventId().equals(resposta.creationEventId())) {
            throw new ConfirmacaoEncerramentoRecusadaException("pedido de encerramento desconhecido ou incompatível");
        }
        var original = transacoes.buscarPorId(resposta.transactionId())
                .orElseThrow(() -> new ConfirmacaoEncerramentoRecusadaException("estado terminal incompatível com confirmação"));
        if (resposta.resultado() == RespostaEncerramentoRecebida.Resultado.DECISAO_EXISTENTE) {
            validarFinanceiro(resposta, original.status());
            if (existente.isEmpty()) { respostas.inserir(resposta); }
            return new AplicacaoEncerramento(resposta, Optional.empty());
        }
        if (original.status() != StatusTransacao.PENDENTE) {
            throw new ConfirmacaoEncerramentoRecusadaException("estado terminal incompatível com confirmação");
        }
        var finalizada = original.confirmarFalhaTecnica();
        if (existente.isEmpty()) { respostas.inserir(resposta); }
        var transicao = transicao(resposta, finalizada.status(), clock.instant().truncatedTo(ChronoUnit.MICROS));
        falhas.registrarFalhaConfirmada(transicao);
        return new AplicacaoEncerramento(resposta, Optional.of(transicao));
    }

    private TransicaoRecebida transicao(RespostaEncerramentoRecebida resposta, StatusTransacao estado, Instant aplicadoEm) {
        return new TransicaoRecebida(resposta.eventId(), resposta.transactionId(), "EncerramentoTransacaoRespondido", 1,
                resposta.correlationId(), resposta.causationId(), StatusTransacao.PENDENTE, estado,
                "processamento-service/EncerramentoTransacaoRespondido.v1", resposta.occurredAt(), aplicadoEm);
    }

    private void validarFinanceiro(RespostaEncerramentoRecebida resposta, StatusTransacao estado) {
        var financeiro = historico.buscarPorEvento(resposta.outputEventId().orElseThrow());
        if (estado == StatusTransacao.PENDENTE && financeiro.isEmpty()) { return; }
        if (financeiro.isPresent()) {
            var registro = financeiro.orElseThrow();
            if ((estado == StatusTransacao.APROVADA || estado == StatusTransacao.REJEITADA)
                    && registro.transactionId().equals(resposta.transactionId())
                    && registro.causationId().equals(resposta.creationEventId())
                    && registro.eventType().equals("TransacaoProcessada") && registro.eventVersion() == 1
                    && registro.estadoFinal() == estado) { return; }
        }
        throw new ConfirmacaoEncerramentoRecusadaException("saída financeira incompatível com encerramento");
    }

    private ConfirmacaoEncerramentoRecusadaException divergente() {
        return new ConfirmacaoEncerramentoRecusadaException("resposta de encerramento divergente");
    }
}
