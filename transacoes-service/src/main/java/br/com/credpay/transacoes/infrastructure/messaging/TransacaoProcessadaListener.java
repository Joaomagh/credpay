package br.com.credpay.transacoes.infrastructure.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "credpay.transacoes.consumer",
        name = {"topology.enabled", "listener.enabled"}, havingValue = "true")
final class TransacaoProcessadaListener {
}
