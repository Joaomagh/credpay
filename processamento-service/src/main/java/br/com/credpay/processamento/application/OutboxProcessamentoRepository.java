package br.com.credpay.processamento.application;

import java.util.Optional;
import java.util.UUID;

public interface OutboxProcessamentoRepository {

    void adicionar(EventoSaidaPendente evento);

    Optional<EventoSaidaPendente> buscarPorEventId(UUID eventId);
}
