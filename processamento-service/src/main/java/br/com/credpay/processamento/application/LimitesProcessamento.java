package br.com.credpay.processamento.application;

import java.math.BigDecimal;
import java.util.Currency;

public interface LimitesProcessamento {

    BigDecimal limitePara(Currency moeda);
}
