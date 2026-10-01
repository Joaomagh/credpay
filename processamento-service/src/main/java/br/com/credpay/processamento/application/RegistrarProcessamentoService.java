package br.com.credpay.processamento.application;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrarProcessamentoService {

    private final ProcessamentoRepository repository;
    private final ProcessarTransacaoService decisor;
    private final Clock clock;
    private final GeradorEventIdSaida gerador;
    private final OutboxProcessamentoRepository outbox;
    private final ObjectMapper objectMapper;

    public RegistrarProcessamentoService(ProcessamentoRepository repository,
            ProcessarTransacaoService decisor, Clock clock, GeradorEventIdSaida gerador,
            OutboxProcessamentoRepository outbox, ObjectMapper objectMapper) {
        this.repository = repository;
        this.decisor = decisor;
        this.clock = clock;
        this.gerador = gerador;
        this.outbox = outbox;
        this.objectMapper = objectMapper;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProcessamentoRegistrado executar(TransacaoCriadaRecebida entrada) {
        Objects.requireNonNull(entrada, "entrada");
        repository.bloquearIdentidades(entrada.eventId(), entrada.transactionId());
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
        outbox.adicionar(criarEventoSaida(resultado));
        return resultado;
    }

    private EventoSaidaPendente criarEventoSaida(ProcessamentoRegistrado resultado) {
        var payload = new TransacaoProcessadaPayload(
                resultado.outputEventId(), "TransacaoProcessada", 1,
                resultado.processedAt().toString(), resultado.transactionId(),
                resultado.eventId(),
                new TransacaoProcessadaDados(resultado.transactionId(), resultado.status().name()));
        try {
            return new EventoSaidaPendente(resultado.outputEventId(), resultado.transactionId(),
                    "TransacaoProcessada", 1, objectMapper.writeValueAsString(payload),
                    resultado.processedAt());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("nao foi possivel serializar TransacaoProcessada", exception);
        }
    }

    private record TransacaoProcessadaPayload(
            UUID eventId, String eventType, int eventVersion, String occurredAt,
            UUID correlationId, UUID causationId, TransacaoProcessadaDados data) {
    }

    private record TransacaoProcessadaDados(UUID transactionId, String status) {
    }
}
