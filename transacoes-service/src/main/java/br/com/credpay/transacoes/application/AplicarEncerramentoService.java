package br.com.credpay.transacoes.application;

import java.util.Optional;
import java.time.Clock;
import java.time.temporal.ChronoUnit;

import br.com.credpay.transacoes.domain.StatusTransacao;

import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

public class AplicarEncerramentoService {

    private final TransacaoRepository transacoes;
    private final TransicaoRepository historico;
    private final RespostaEncerramentoRepository respostas;
    private final FalhaConfirmadaRepository falhas;
    private final Clock clock;

    public AplicarEncerramentoService(TransacaoRepository transacoes, TransicaoRepository historico,
            RespostaEncerramentoRepository respostas, FalhaConfirmadaRepository falhas, Clock clock) {
        this.transacoes = transacoes;
        this.historico = historico;
        this.respostas = respostas;
        this.falhas = falhas;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AplicacaoEncerramento executar(RespostaEncerramentoRecebida resposta) {
        historico.bloquearIdentidades(resposta.eventId(), resposta.transactionId());
        var original = transacoes.buscarPorId(resposta.transactionId()).orElseThrow(TransicaoRecusadaException::new);
        var finalizada = original.confirmarFalhaTecnica();
        respostas.inserir(resposta);
        var transicao = new TransicaoRecebida(resposta.eventId(), resposta.transactionId(),
                "EncerramentoTransacaoRespondido", 1, resposta.correlationId(), resposta.causationId(),
                StatusTransacao.PENDENTE, finalizada.status(), "processamento-service/EncerramentoTransacaoRespondido.v1",
                resposta.occurredAt(), clock.instant().truncatedTo(ChronoUnit.MICROS));
        falhas.registrarFalhaConfirmada(transicao);
        return new AplicacaoEncerramento(resposta, Optional.of(transicao));
    }
}
