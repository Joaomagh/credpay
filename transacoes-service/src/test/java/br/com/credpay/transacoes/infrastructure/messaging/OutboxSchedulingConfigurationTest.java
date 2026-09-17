package br.com.credpay.transacoes.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import br.com.credpay.transacoes.application.PublicarOutboxService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.Scheduled;

class OutboxSchedulingConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(PublicarOutboxService.class, () -> mock(PublicarOutboxService.class))
            .withUserConfiguration(OutboxSchedulingConfiguration.class);

    @Test
    void contexto_deveManterSchedulerDesabilitado_porPadrao() {
        contextRunner.run(contexto ->
                assertThat(contexto).doesNotHaveBean(OutboxPublisherScheduler.class));
    }

    @Test
    void contexto_deveCriarScheduler_quandoHabilitadoExplicitamente() {
        contextRunner
                .withPropertyValues(
                        "credpay.outbox.publisher.enabled=true",
                        "credpay.outbox.publisher.interval=PT1H")
                .run(contexto ->
                        assertThat(contexto).hasSingleBean(OutboxPublisherScheduler.class));
    }

    @Test
    void publicarPendencias_deveDelegarLoteAoCasoDeUso() {
        var service = mock(PublicarOutboxService.class);
        var scheduler = new OutboxPublisherScheduler(service);

        scheduler.publicarPendencias();

        verify(service).publicarLote();
    }

    @Test
    void intervalo_deveSerConfiguravelPorPropriedade() throws Exception {
        var metodo = OutboxPublisherScheduler.class.getDeclaredMethod("publicarPendencias");
        var scheduled = metodo.getAnnotation(Scheduled.class);

        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${credpay.outbox.publisher.interval:PT1S}");
        assertThat(scheduled.initialDelayString())
                .isEqualTo("${credpay.outbox.publisher.interval:PT1S}");
    }
}
