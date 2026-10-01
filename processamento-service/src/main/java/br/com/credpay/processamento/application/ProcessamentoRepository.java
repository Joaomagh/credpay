package br.com.credpay.processamento.application;

import java.util.Optional;
import java.util.UUID;

public interface ProcessamentoRepository {

    void inserir(ProcessamentoRegistrado processamento);

    Optional<ProcessamentoRegistrado> buscarPorEventId(UUID eventId);
}
