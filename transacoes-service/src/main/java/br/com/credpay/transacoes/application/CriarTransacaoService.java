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

        return paraResultado(transacao);
    }

    @Override
    @Transactional
    public Resultado executar(UUID chaveIdempotencia, BigDecimal valor, Currency moeda) {
        var candidata = Transacao.criar(UUID.randomUUID(), valor, moeda);
        var existente = repository.buscarPorChaveIdempotencia(chaveIdempotencia);

        if (existente.isPresent()) {
            var transacao = existente.orElseThrow();
            if (transacao.valor().compareTo(candidata.valor()) != 0
                    || !transacao.moeda().equals(candidata.moeda())) {
                throw new ConflitoIdempotenciaException();
            }
            return paraResultado(transacao);
        }

        repository.inserir(chaveIdempotencia, candidata);
        return paraResultado(candidata);
    }

    private Resultado paraResultado(Transacao transacao) {
        return new Resultado(
                transacao.id(),
                transacao.valor(),
                transacao.moeda(),
                transacao.status());
    }
}
