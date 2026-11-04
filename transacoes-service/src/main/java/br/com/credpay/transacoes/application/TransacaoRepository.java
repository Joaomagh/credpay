package br.com.credpay.transacoes.application;

import java.util.Optional;
import java.util.UUID;

import br.com.credpay.transacoes.domain.Transacao;

public interface TransacaoRepository {

    void inserir(Transacao transacao);

    void inserir(UUID chaveIdempotencia, Transacao transacao);

    void bloquearChaveIdempotencia(UUID chaveIdempotencia);

    Optional<Transacao> buscarPorId(UUID id);

    // Escopo HTTP. Conta nula seleciona registros legados sem dono.
    Optional<Transacao> buscarPorIdNaConta(UUID id, UUID contaId);

    Optional<Transacao> buscarPorChaveIdempotencia(UUID chaveIdempotencia);
}
