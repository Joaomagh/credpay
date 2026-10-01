package br.com.credpay.processamento.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import br.com.credpay.processamento.application.EventoSaidaPendente;
import br.com.credpay.processamento.application.OutboxProcessamentoRepository;
import org.springframework.stereotype.Repository;

@Repository
class OutboxProcessamentoJdbcRepository implements OutboxProcessamentoRepository {

    @Override
    public void adicionar(EventoSaidaPendente evento) {
    }

    @Override
    public Optional<EventoSaidaPendente> buscarPorEventId(UUID eventId) {
        return Optional.empty();
    }
}
