package br.com.credpay.processamento.application;

import br.com.credpay.processamento.domain.ResultadoProcessamento;
import java.math.BigDecimal;
import java.util.Currency;

import org.springframework.stereotype.Service;

@Service
public final class ProcessarTransacaoService {

    private final LimitesProcessamento limites;

    public ProcessarTransacaoService(LimitesProcessamento limites) {
        this.limites = limites;
    }

    public ResultadoProcessamento executar(BigDecimal valor, Currency moeda) {
        var limite = limites.limitePara(moeda);
        return ResultadoProcessamento.decidir(valor, moeda, limite);
    }
}
