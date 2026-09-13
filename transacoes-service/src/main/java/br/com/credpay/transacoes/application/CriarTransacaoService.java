package br.com.credpay.transacoes.application;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

import br.com.credpay.transacoes.domain.Transacao;
import org.springframework.stereotype.Service;

@Service
final class CriarTransacaoService implements CriarTransacao {

    @Override
    public Resultado executar(BigDecimal valor, Currency moeda) {
        var transacao = Transacao.criar(UUID.randomUUID(), valor, moeda);

        return new Resultado(
                transacao.id(),
                transacao.valor(),
                transacao.moeda(),
                transacao.status());
    }
}
