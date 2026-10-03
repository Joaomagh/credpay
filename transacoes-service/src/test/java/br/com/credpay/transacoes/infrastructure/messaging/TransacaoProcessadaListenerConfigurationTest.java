package br.com.credpay.transacoes.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import br.com.credpay.transacoes.application.AplicarResultadoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TransacaoProcessadaListenerConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RabbitAutoConfiguration.class))
            .withUserConfiguration(TransacaoProcessadaListener.class, TransacaoProcessadaListenerConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(AplicarResultadoService.class, () -> mock(AplicarResultadoService.class))
            .withPropertyValues("spring.rabbitmq.listener.simple.auto-startup=false");

    @Test
    void listener_devePermanecerAusentePorPadrao() {
        runner.run(context -> assertThat(context).doesNotHaveBean(TransacaoProcessadaListener.class)
                .doesNotHaveBean("transacaoProcessadaListenerContainerFactory"));
    }

    @ParameterizedTest
    @CsvSource({"false,false,false", "true,false,false", "false,true,false", "true,true,true"})
    void listener_deveExigirAsDuasFlags(boolean topologia, boolean listener, boolean habilitado) {
        runner.withPropertyValues("credpay.transacoes.consumer.topology.enabled=" + topologia,
                "credpay.transacoes.consumer.listener.enabled=" + listener).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.containsBean("transacaoProcessadaListener")).isEqualTo(habilitado);
            assertThat(context.containsBean("transacaoProcessadaListenerContainerFactory")).isEqualTo(habilitado);
        });
    }
}
