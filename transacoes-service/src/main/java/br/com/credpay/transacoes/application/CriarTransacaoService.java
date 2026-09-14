package br.com.credpay.transacoes.application;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

import br.com.credpay.transacoes.domain.Transacao;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CriarTransacaoService implements CriarTransacao {

    private final TransacaoRepository repository;

    CriarTransacaoService(TransacaoRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public Resultado executar(BigDecimal valor, Currency moeda) {
        var transacao = Transacao.criar(UUID.randomUUID(), valor, moeda);
        repository.inserir(transacao);

        return new Resultado(
                transacao.id(),
                transacao.valor(),
                transacao.moeda(),
                transacao.status());
    }
}
