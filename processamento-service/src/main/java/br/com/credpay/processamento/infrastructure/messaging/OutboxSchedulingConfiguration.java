package br.com.credpay.processamento.infrastructure.messaging;

import br.com.credpay.processamento.application.PublicarOutboxProcessamento;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "credpay.outbox.publisher", name = "enabled", havingValue = "true")
class OutboxSchedulingConfiguration {

    @Bean
    OutboxPublisherScheduler outboxPublisherScheduler(PublicarOutboxProcessamento publicador) {
        return new OutboxPublisherScheduler(publicador);
    }
}
