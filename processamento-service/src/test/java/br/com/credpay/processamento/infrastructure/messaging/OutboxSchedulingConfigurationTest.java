package br.com.credpay.processamento.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import br.com.credpay.processamento.application.PublicarOutboxProcessamento;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.Scheduled;

class OutboxSchedulingConfigurationTest {

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = RabbitMqSaidaConfiguration.class,
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.REGEX,
                    pattern = "br\\.com\\.credpay\\.processamento\\.infrastructure\\.messaging\\.OutboxSchedulingConfiguration"))
    static class SchedulerScan {
    }

    private final PublicarOutboxProcessamento publicador = mock(PublicarOutboxProcessamento.class);
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(PublicarOutboxProcessamento.class, () -> publicador)
            .withUserConfiguration(SchedulerScan.class);

    @Test
    void contexto_deveManterSchedulerDesabilitado_porPadrao() {
        contextRunner.run(contexto -> assertThat(contexto).doesNotHaveBean("outboxPublisherScheduler"));
    }

    @Test
    void contexto_deveCriarScheduler_quandoHabilitadoExplicitamente() {
        contextRunner
                .withPropertyValues(
                        "credpay.outbox.publisher.enabled=true",
                        "credpay.outbox.publisher.interval=PT1H")
                .run(contexto -> {
                    assertThat(contexto).hasBean("outboxPublisherScheduler");
                    var scheduler = contexto.getBean("outboxPublisherScheduler");
                    var metodo = scheduler.getClass().getDeclaredMethod("publicarPendencias");
                    var scheduled = metodo.getAnnotation(Scheduled.class);
                    assertThat(scheduled.fixedDelayString())
                            .isEqualTo("${credpay.outbox.publisher.interval:PT1S}");
                    assertThat(scheduled.initialDelayString())
                            .isEqualTo("${credpay.outbox.publisher.interval:PT1S}");
                    metodo.invoke(scheduler);
                    verify(publicador).publicarProximo();
                });
    }
}
