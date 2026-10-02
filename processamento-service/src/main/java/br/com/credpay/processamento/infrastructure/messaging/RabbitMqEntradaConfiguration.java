package br.com.credpay.processamento.infrastructure.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "credpay.processamento.consumer.topology", name = "enabled", havingValue = "true")
class RabbitMqEntradaConfiguration {

    static final String ENTRADA = "credpay.processamento.transacao-criada.v1";
    static final String DLQ = "credpay.processamento.transacao-criada.dlq.v1";
    static final String DLX = "credpay.processamento.dlx.v1";
    static final String ROTA_DLQ = "transacao.criada.dlq.v1";
    static final String EXCHANGE_PRODUTOR = "credpay.transacoes.v1";
    static final String ROTA_ENTRADA = "transacao.criada.v1";

    @Bean
    Queue transacaoCriadaQueue() {
        return QueueBuilder.durable(ENTRADA).quorum().build();
    }

    @Bean
    Queue transacaoCriadaDlq() {
        return QueueBuilder.durable(DLQ).quorum().build();
    }

    @Bean
    DirectExchange processamentoDeadLetterExchange() {
        return new DirectExchange(DLX, true, false);
    }

    @Bean
    Binding transacaoCriadaBinding() {
        return new Binding(ENTRADA, Binding.DestinationType.QUEUE, EXCHANGE_PRODUTOR, ROTA_ENTRADA, null);
    }

    @Bean
    Binding transacaoCriadaDlqBinding() {
        return new Binding(DLQ, Binding.DestinationType.QUEUE, DLX, ROTA_DLQ, null);
    }
}
