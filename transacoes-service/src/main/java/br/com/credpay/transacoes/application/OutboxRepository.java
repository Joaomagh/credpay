package br.com.credpay.transacoes.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository {

    void adicionar(EventoOutbox evento);

    List<EventoOutbox> buscarPendentes(int limite);

    void marcarPublicado(UUID eventId, Instant publicadoEm);
}
