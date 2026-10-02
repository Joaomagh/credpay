package br.com.credpay.processamento.support;

import org.springframework.amqp.core.DirectExchange;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class ProducerExchangeFixture {

    @Bean
    DirectExchange producerExchangeFixture() {
        return new DirectExchange("credpay.transacoes.v1", true, false);
    }
}
