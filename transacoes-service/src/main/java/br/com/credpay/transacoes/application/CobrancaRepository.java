package br.com.credpay.transacoes.application;

import java.util.Optional;
import java.util.UUID;

import br.com.credpay.transacoes.domain.Cobranca;

public interface CobrancaRepository {
    void bloquearChave(UUID contaId, UUID chave);
    Optional<Cobranca> buscarPorChave(UUID contaId, UUID chave);
    void inserir(UUID chave, Cobranca cobranca);
}
