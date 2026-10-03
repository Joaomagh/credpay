package br.com.credpay.processamento.infrastructure.messaging;

import java.util.Map;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "credpay.processamento.consumer",
        name = {"topology.enabled", "listener.enabled"}, havingValue = "true")
class TransacaoCriadaRetryConfiguration {

    @Bean
    RetryOperationsInterceptor transacaoCriadaRetryInterceptor() {
        var retry = new RetryTemplate();
        retry.setRetryPolicy(new SimpleRetryPolicy(3,
                Map.of(AmqpRejectAndDontRequeueException.class, false), true, true));
        var backoff = new ExponentialBackOffPolicy();
        backoff.setInitialInterval(1_000);
        backoff.setMultiplier(2);
        backoff.setMaxInterval(2_000);
        retry.setBackOffPolicy(backoff);

        return RetryInterceptorBuilder.stateless().retryOperations(retry)
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
            RetryOperationsInterceptor transacaoCriadaRetryInterceptor) {
        var factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setAdviceChain(transacaoCriadaRetryInterceptor);
        return factory;
    }
}
