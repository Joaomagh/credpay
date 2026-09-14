package br.com.credpay.transacoes.application;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class BuscarTransacaoService implements BuscarTransacao {

    private final TransacaoRepository repository;

    BuscarTransacaoService(TransacaoRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public Resultado executar(UUID id) {
        var transacao = repository.buscarPorId(id)
                .orElseThrow(TransacaoNaoEncontradaException::new);

        return new Resultado(
                transacao.id(),
                transacao.valor(),
                transacao.moeda(),
                transacao.status());
    }
}
