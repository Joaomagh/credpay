package br.com.credpay.transacoes.infrastructure.messaging;

import br.com.credpay.transacoes.application.PublicarOutboxService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
        prefix = "credpay.outbox.publisher",
        name = "enabled",
        havingValue = "true")
class OutboxSchedulingConfiguration {

    @Bean
    OutboxPublisherScheduler outboxPublisherScheduler(PublicarOutboxService service) {
        return new OutboxPublisherScheduler(service);
    }
}
