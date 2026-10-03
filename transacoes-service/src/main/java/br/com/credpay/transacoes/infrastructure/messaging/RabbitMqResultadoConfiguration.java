package br.com.credpay.transacoes.infrastructure.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "credpay.transacoes.consumer.topology", name = "enabled", havingValue = "true")
class RabbitMqResultadoConfiguration {

    static final String ENTRADA = "credpay.transacoes.transacao-processada.v1";
    static final String DLQ = "credpay.transacoes.transacao-processada.dlq.v1";
    static final String DLX = "credpay.transacoes.dlx.v1";
    static final String ROTA_DLQ = "transacao.processada.dlq.v1";
    static final String EXCHANGE_PRODUTOR = "credpay.processamento.v1";
    static final String ROTA_ENTRADA = "transacao.processada.v1";

    @Bean
    Queue transacaoProcessadaQueue() {
        return QueueBuilder.durable(ENTRADA).quorum().build();
    }

    @Bean
    Queue transacaoProcessadaDlq() {
        return QueueBuilder.durable(DLQ).quorum().build();
    }

    @Bean
    DirectExchange transacoesDeadLetterExchange() {
        return new DirectExchange(DLX, true, false);
    }

    @Bean
    Binding transacaoProcessadaBinding() {
        return new Binding(ENTRADA, Binding.DestinationType.QUEUE, EXCHANGE_PRODUTOR, ROTA_ENTRADA, null);
    }

    @Bean
    Binding transacaoProcessadaDlqBinding() {
        return new Binding(DLQ, Binding.DestinationType.QUEUE, DLX, ROTA_DLQ, null);
    }
}
