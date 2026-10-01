package br.com.credpay.processamento.infrastructure.messaging;

import org.springframework.amqp.core.DirectExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class RabbitMqSaidaConfiguration {

    static final String EVENTOS_EXCHANGE = "credpay.processamento.v1";
    static final String TRANSACAO_PROCESSADA_ROUTING_KEY = "transacao.processada.v1";

    @Bean
    DirectExchange processamentoEventosExchange() {
        return new DirectExchange(EVENTOS_EXCHANGE, true, false);
    }
}
