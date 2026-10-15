package br.com.credpay.transacoes.infrastructure.messaging;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.rabbit.config.StatelessRetryOperationsInterceptor;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "credpay.transacoes.consumer",
        name = {"topology.enabled", "listener.enabled"}, havingValue = "true")
class TransacaoProcessadaListenerConfiguration {

    @Bean
    StatelessRetryOperationsInterceptor transacaoProcessadaRetryInterceptor() {
        return RetryInterceptorBuilder.stateless()
                .maxRetries(2)
                .backOffOptions(1_000, 2, 2_000)
                .configureRetryPolicy(policy -> policy.excludes(AmqpRejectAndDontRequeueException.class))
                .recoverer((mensagem, causa) -> {
                    for (var atual = causa; atual != null; atual = atual.getCause()) {
                        if (atual instanceof AmqpRejectAndDontRequeueException permanente) throw permanente;
                    }
                    throw new AmqpRejectAndDontRequeueException(
                            "TransacaoProcessada com falha operacional apos 3 tentativas");
                }).build();
    }

    @Bean
    SimpleRabbitListenerContainerFactory transacaoProcessadaListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory,
            StatelessRetryOperationsInterceptor transacaoProcessadaRetryInterceptor) {
        var factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setPrefetchCount(1);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        factory.setAdviceChain(transacaoProcessadaRetryInterceptor);
        return factory;
    }
}
