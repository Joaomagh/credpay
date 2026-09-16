package br.com.credpay.transacoes.application;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

import br.com.credpay.transacoes.domain.StatusTransacao;

public interface CriarTransacao {

    Resultado executar(BigDecimal valor, Currency moeda);

    Resultado executar(UUID chaveIdempotencia, BigDecimal valor, Currency moeda);

    record Resultado(UUID id, BigDecimal valor, Currency moeda, StatusTransacao status) {
    }
}
