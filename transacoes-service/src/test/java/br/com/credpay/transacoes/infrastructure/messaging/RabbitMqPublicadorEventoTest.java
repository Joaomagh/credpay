package br.com.credpay.transacoes.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import br.com.credpay.transacoes.application.EventoOutbox;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.mockito.ArgumentCaptor;

class RabbitMqPublicadorEventoTest {

    @Test
    void publicar_deveEnviarContratoPersistente_quandoBrokerConfirmarSemRetorno() {
        var rabbitTemplate = mock(RabbitTemplate.class);
        var publicador = new RabbitMqPublicadorEvento(rabbitTemplate);
        var evento = evento();
        doAnswer(invocacao -> {
            var correlacao = invocacao.getArgument(3, CorrelationData.class);
            correlacao.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(
                eq(RabbitMqConfiguration.TRANSACAO_EVENTOS_EXCHANGE),
                eq(RabbitMqConfiguration.TRANSACAO_CRIADA_ROUTING_KEY),
                any(Message.class),
                any(CorrelationData.class));

        var confirmado = publicador.publicar(evento);

        assertThat(confirmado).isTrue();
        var mensagem = ArgumentCaptor.forClass(Message.class);
        var correlacao = ArgumentCaptor.forClass(CorrelationData.class);
        org.mockito.Mockito.verify(rabbitTemplate).send(
                eq(RabbitMqConfiguration.TRANSACAO_EVENTOS_EXCHANGE),
                eq(RabbitMqConfiguration.TRANSACAO_CRIADA_ROUTING_KEY),
                mensagem.capture(),
                correlacao.capture());
        assertThat(new String(mensagem.getValue().getBody(), StandardCharsets.UTF_8))
                .isEqualTo(evento.payload());
        assertThat(mensagem.getValue().getMessageProperties().getContentType())
                .isEqualTo("application/json");
        assertThat(mensagem.getValue().getMessageProperties().getContentEncoding())
                .isEqualTo(StandardCharsets.UTF_8.name());
        assertThat(mensagem.getValue().getMessageProperties().getDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(mensagem.getValue().getMessageProperties().getMessageId())
                .isEqualTo(evento.eventId().toString());
        assertThat(mensagem.getValue().getMessageProperties().getType())
                .isEqualTo(evento.eventType());
        assertThat(mensagem.getValue().getMessageProperties().getCorrelationId())
                .isEqualTo(evento.aggregateId().toString());
        assertThat(correlacao.getValue().getId()).isEqualTo(evento.eventId().toString());
    }

    @Test
    void publicar_deveFalhar_quandoMensagemForRetornadaMesmoComAck() {
        var rabbitTemplate = mock(RabbitTemplate.class);
        var publicador = new RabbitMqPublicadorEvento(rabbitTemplate);
        doAnswer(invocacao -> {
            var mensagem = invocacao.getArgument(2, Message.class);
            var correlacao = invocacao.getArgument(3, CorrelationData.class);
            correlacao.setReturned(new ReturnedMessage(
                    mensagem,
                    312,
                    "NO_ROUTE",
                    RabbitMqConfiguration.TRANSACAO_EVENTOS_EXCHANGE,
                    RabbitMqConfiguration.TRANSACAO_CRIADA_ROUTING_KEY));
            correlacao.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(any(String.class), any(String.class), any(), any());

        var confirmado = publicador.publicar(evento());

        assertThat(confirmado).isFalse();
    }

    @Test
    void publicar_deveFalhar_quandoBrokerResponderNack() {
        var rabbitTemplate = mock(RabbitTemplate.class);
        var publicador = new RabbitMqPublicadorEvento(rabbitTemplate);
        doAnswer(invocacao -> {
            var correlacao = invocacao.getArgument(3, CorrelationData.class);
            correlacao.getFuture().complete(new CorrelationData.Confirm(false, "broker indisponível"));
            return null;
        }).when(rabbitTemplate).send(any(String.class), any(String.class), any(), any());

        var confirmado = publicador.publicar(evento());

        assertThat(confirmado).isFalse();
    }

    private EventoOutbox evento() {
        return new EventoOutbox(
                UUID.fromString("6dc8d48d-5b20-4ee9-ac7e-832e421121aa"),
                UUID.fromString("7b8b61c2-9f63-4d74-9f8d-89cb52de0ed9"),
                "TransacaoCriada",
                1,
                "{\"eventType\":\"TransacaoCriada\"}",
                Instant.parse("2026-09-17T12:00:00Z"));
    }
}
