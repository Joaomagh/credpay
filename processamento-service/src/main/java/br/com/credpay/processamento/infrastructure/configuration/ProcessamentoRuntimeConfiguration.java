package br.com.credpay.processamento.infrastructure.configuration;

import br.com.credpay.processamento.application.GeradorEventIdSaida;
import java.time.Clock;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ProcessamentoRuntimeConfiguration {

    @Bean
    Clock processamentoClock() {
        return Clock.systemUTC();
    }

    @Bean
    GeradorEventIdSaida geradorEventIdSaida() {
        return UUID::randomUUID;
    }
}
