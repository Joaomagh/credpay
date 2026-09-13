package br.com.credpay.transacoes.application;

import java.util.Optional;
import java.util.UUID;

import br.com.credpay.transacoes.domain.Transacao;

public interface TransacaoRepository {

    void inserir(Transacao transacao);

    Optional<Transacao> buscarPorId(UUID id);
}
