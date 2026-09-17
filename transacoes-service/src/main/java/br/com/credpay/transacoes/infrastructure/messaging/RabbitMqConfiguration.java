package br.com.credpay.transacoes.infrastructure.messaging;

import org.springframework.amqp.core.DirectExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class RabbitMqConfiguration {

    static final String TRANSACAO_EVENTOS_EXCHANGE = "credpay.transacoes.v1";
    static final String TRANSACAO_CRIADA_ROUTING_KEY = "transacao.criada.v1";

    @Bean
    DirectExchange transacaoEventosExchange() {
        return new DirectExchange(TRANSACAO_EVENTOS_EXCHANGE, true, false);
    }
}
