package br.com.credpay.transacoes.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Currency;
import java.util.UUID;

import br.com.credpay.transacoes.domain.Transacao;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CriarTransacaoService implements CriarTransacao {

    private final TransacaoRepository repository;
    private final OutboxRepository outboxRepository;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    CriarTransacaoService(
            TransacaoRepository repository,
            OutboxRepository outboxRepository,
            Clock clock,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public Resultado executar(UUID chaveIdempotencia, BigDecimal valor, Currency moeda) {
        var candidata = Transacao.criar(UUID.randomUUID(), valor, moeda);
        repository.bloquearChaveIdempotencia(chaveIdempotencia);
        var existente = repository.buscarPorChaveIdempotencia(chaveIdempotencia);

        if (existente.isPresent()) {
            var transacao = existente.orElseThrow();
            if (transacao.valor().compareTo(candidata.valor()) != 0
                    || !transacao.moeda().equals(candidata.moeda())) {
                throw new ConflitoIdempotenciaException();
            }
            return paraResultado(transacao);
        }

        repository.inserir(chaveIdempotencia, candidata);
        outboxRepository.adicionar(criarEvento(candidata));
        return paraResultado(candidata);
    }

    private EventoOutbox criarEvento(Transacao transacao) {
        var eventId = UUID.randomUUID();
        var occurredAt = clock.instant();
        var payload = new TransacaoCriadaPayload(
                eventId,
                "TransacaoCriada",
                1,
                occurredAt.toString(),
                transacao.id(),
                new TransacaoCriadaDados(
                        transacao.id(),
                        transacao.valor().toPlainString(),
                        transacao.moeda().getCurrencyCode(),
                        transacao.status().name()));

        try {
            return new EventoOutbox(
                    eventId,
                    transacao.id(),
                    "TransacaoCriada",
                    1,
                    objectMapper.writeValueAsString(payload),
                    occurredAt);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("não foi possível serializar TransacaoCriada", exception);
        }
    }

    private Resultado paraResultado(Transacao transacao) {
        return new Resultado(
                transacao.id(),
                transacao.valor(),
                transacao.moeda(),
                transacao.status());
    }

    private record TransacaoCriadaPayload(
            UUID eventId,
            String eventType,
            int eventVersion,
            String occurredAt,
            UUID correlationId,
            TransacaoCriadaDados data) {
    }

    private record TransacaoCriadaDados(
            UUID transactionId,
            String amount,
            String currency,
            String status) {
    }
}
