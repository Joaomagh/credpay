package br.com.credpay.processamento.application;

import br.com.credpay.processamento.domain.ProcessadorTransacao;
import br.com.credpay.processamento.domain.StatusProcessamento;
import java.math.BigDecimal;
import java.util.Currency;

import org.springframework.stereotype.Service;

@Service
public final class ProcessarTransacaoService {

    private final LimitesProcessamento limites;

    public ProcessarTransacaoService(LimitesProcessamento limites) {
        this.limites = limites;
    }

    public StatusProcessamento executar(BigDecimal valor, Currency moeda) {
        return ProcessadorTransacao.processar(valor, limites.limitePara(moeda));
    }
}
