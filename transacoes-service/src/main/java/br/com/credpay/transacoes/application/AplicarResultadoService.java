package br.com.credpay.transacoes.application;

import java.time.Clock;
import java.time.temporal.ChronoUnit;

import br.com.credpay.transacoes.domain.StatusTransacao;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AplicarResultadoService {

    private final TransacaoRepository transacoes;
    private final TransicaoRepository historico;
    private final Clock clock;

    public AplicarResultadoService(TransacaoRepository transacoes, TransicaoRepository historico, Clock clock) {
        this.transacoes = transacoes;
        this.historico = historico;
        this.clock = clock;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransicaoRecebida executar(TransacaoProcessadaRecebida entrada) {
        historico.bloquearIdentidades(entrada.eventId(), entrada.transactionId());
        var existente = historico.buscarPorEvento(entrada.eventId());
        if (existente.isPresent()) {
            var registrada = existente.orElseThrow();
            if (registrada.correspondeA(entrada)) {
                return registrada;
            }
            throw new ConflitoResultadoException();
        }
        var original = transacoes.buscarPorId(entrada.transactionId()).orElseThrow(TransicaoRecusadaException::new);
        if (original.status() != StatusTransacao.PENDENTE) {
            throw new ConflitoResultadoException();
        }
        if (!historico.existeCriacao(entrada.causationId(), entrada.transactionId())) {
            throw new TransicaoRecusadaException();
        }
        var finalizada = original.concluir(entrada.status());
        var transicao = new TransicaoRecebida(entrada.eventId(), entrada.transactionId(), "TransacaoProcessada", 1,
                entrada.correlationId(), entrada.causationId(), StatusTransacao.PENDENTE, finalizada.status(),
                "processamento-service/TransacaoProcessada.v1", entrada.occurredAt(),
                clock.instant().truncatedTo(ChronoUnit.MICROS));
        historico.registrar(transicao);
        return transicao;
    }
}
