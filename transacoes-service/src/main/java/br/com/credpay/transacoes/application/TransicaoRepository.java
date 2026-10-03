package br.com.credpay.transacoes.application;

import java.util.Optional;
import java.util.UUID;

public interface TransicaoRepository {

    void registrar(TransicaoRecebida transicao);

    Optional<TransicaoRecebida> buscarPorEvento(UUID eventId);

    boolean existeCriacao(UUID causationId, UUID transactionId);
}
