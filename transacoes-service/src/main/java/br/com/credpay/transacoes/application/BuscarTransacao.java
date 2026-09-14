package br.com.credpay.transacoes.application;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

import br.com.credpay.transacoes.domain.StatusTransacao;

public interface BuscarTransacao {

    Resultado executar(UUID id);

    record Resultado(UUID id, BigDecimal valor, Currency moeda, StatusTransacao status) {
    }
}
