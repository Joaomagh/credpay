package br.com.credpay.transacoes.application;

import java.util.Optional;
import java.util.UUID;

public interface RespostaEncerramentoRepository {
    void inserir(RespostaEncerramentoRecebida resposta);

    Optional<RespostaEncerramentoRecebida> buscarPorEvento(UUID eventId);
}
