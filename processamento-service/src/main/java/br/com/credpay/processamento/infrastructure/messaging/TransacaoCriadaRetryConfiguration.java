package br.com.credpay.processamento.infrastructure.messaging;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.rabbit.config.StatelessRetryOperationsInterceptor;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "credpay.processamento.consumer",
        name = {"topology.enabled", "listener.enabled"}, havingValue = "true")
class TransacaoCriadaRetryConfiguration {

    @Bean
    StatelessRetryOperationsInterceptor transacaoCriadaRetryInterceptor() {
        return RetryInterceptorBuilder.stateless()
                .maxRetries(2)
                .backOffOptions(1_000, 2, 2_000)
                .configureRetryPolicy(policy -> policy.excludes(AmqpRejectAndDontRequeueException.class))
                .recoverer((mensagem, causa) -> {
                    for (var atual = causa; atual != null; atual = atual.getCause()) {
                        if (atual instanceof AmqpRejectAndDontRequeueException permanente) {
                            throw permanente;
                        }
                    }
                    throw new AmqpRejectAndDontRequeueException(
                            "TransacaoCriada com falha operacional apos 3 tentativas");
                }).build();
    }

    @Bean
    SimpleRabbitListenerContainerFactory transacaoCriadaListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            StatelessRetryOperationsInterceptor transacaoCriadaRetryInterceptor) {
        var factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAdviceChain(transacaoCriadaRetryInterceptor);
        return factory;
    }
}
