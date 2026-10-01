package br.com.credpay.processamento.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxProcessamentoRepository {

    void adicionar(EventoSaidaPendente evento);

    Optional<EventoSaidaPendente> buscarPorEventId(UUID eventId);

    List<EventoSaidaPendente> buscarPendentes(int limite);

    void marcarPublicado(UUID eventId, Instant publicadoEm);
}
