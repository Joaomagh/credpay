package br.com.credpay.processamento.application;

import java.time.Clock;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrarProcessamentoService {

    private final ProcessamentoRepository repository;
    private final ProcessarTransacaoService decisor;
    private final Clock clock;
    private final GeradorEventIdSaida gerador;

    public RegistrarProcessamentoService(ProcessamentoRepository repository,
            ProcessarTransacaoService decisor, Clock clock, GeradorEventIdSaida gerador) {
        this.repository = repository;
        this.decisor = decisor;
        this.clock = clock;
        this.gerador = gerador;
    }

    @Transactional
    public ProcessamentoRegistrado executar(TransacaoCriadaRecebida entrada) {
        Objects.requireNonNull(entrada, "entrada");
        var existente = repository.buscarPorEventId(entrada.eventId());
        if (existente.isPresent()) {
            if (existente.orElseThrow().correspondeA(entrada)) {
                return existente.orElseThrow();
            }
            throw new ConflitoProcessamentoException("evento divergente");
        }
        if (repository.existePorTransactionId(entrada.transactionId())) {
            throw new ConflitoProcessamentoException("transacao ja processada");
        }

        var decisao = decisor.executar(entrada.valor(), entrada.moeda());
        var resultado = new ProcessamentoRegistrado(
                entrada.eventId(), entrada.transactionId(), "TransacaoCriada", 1,
                entrada.occurredAt(), entrada.correlationId(), "PENDENTE",
                decisao.valor(), decisao.moeda(), decisao.limiteAplicado(), decisao.status(),
                clock.instant(), gerador.gerar());
        repository.inserir(resultado);
        return resultado;
    }
}
