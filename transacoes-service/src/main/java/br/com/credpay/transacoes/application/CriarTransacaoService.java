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
        var transacao = Transacao.criar(valor, moeda);

        return new Resultado(
                UUID.randomUUID(),
                transacao.valor(),
                transacao.moeda(),
                transacao.status());
    }
}
