package br.com.credpay.processamento.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.UUID;

import br.com.credpay.processamento.application.EventoSaidaPendente;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class RabbitMqPublicadorSaidaTest {

    private static final String METRICA = "credpay.messaging.publish.attempts";

    @Test
    void publicar_deveContarUmaConfirmacao_quandoBrokerConfirmarSemRetorno() {
        var rabbit = mock(RabbitTemplate.class);
        doAnswer(invocacao -> {
            CorrelationData correlacao = invocacao.getArgument(3);
            correlacao.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        var metricas = new SimpleMeterRegistry();
        try {
            var publicador = new RabbitMqPublicadorSaida(rabbit, metricas);

            assertThat(publicador.publicar(evento())).isTrue();

            var contador = metricas.find(METRICA).tag("outcome", "confirmed").counter();
            assertThat(contador).isNotNull();
            assertThat(contador.count()).isEqualTo(1.0);
            assertThat(metricas.getMeters()).hasSize(1);
            assertThat(contador.getId().getTags()).hasSize(1);
        } finally {
            metricas.close();
        }
    }

    @Test
    void publicar_deveContarRetornoSemConfirmacao_quandoBrokerConfirmarMensagemSemRota() {
        var rabbit = mock(RabbitTemplate.class);
        doAnswer(invocacao -> {
            CorrelationData correlacao = invocacao.getArgument(3);
            correlacao.setReturned(new ReturnedMessage(new Message(new byte[0]),
                    312, "NO_ROUTE", "exchange", "routing"));
            correlacao.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
        var metricas = new SimpleMeterRegistry();
        try {
            var publicador = new RabbitMqPublicadorSaida(rabbit, metricas);

            assertThat(publicador.publicar(evento())).isFalse();

            var contador = metricas.find(METRICA).tag("outcome", "returned").counter();
            assertThat(contador).isNotNull();
            assertThat(contador.count()).isEqualTo(1.0);
            assertThat(metricas.find(METRICA).tag("outcome", "confirmed").counter()).isNull();
            assertThat(metricas.getMeters()).hasSize(1);
        } finally {
            metricas.close();
        }
    }

    private static EventoSaidaPendente evento() {
        return new EventoSaidaPendente(UUID.randomUUID(), UUID.randomUUID(),
                "TransacaoProcessada", 1, "{}", Instant.parse("2026-10-09T12:00:00Z"));
    }
}
