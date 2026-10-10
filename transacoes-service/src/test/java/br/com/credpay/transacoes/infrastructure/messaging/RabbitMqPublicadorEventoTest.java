package br.com.credpay.transacoes.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import br.com.credpay.transacoes.application.EventoOutbox;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.mockito.ArgumentCaptor;

class RabbitMqPublicadorEventoTest {

    private static final String METRICA = "credpay.messaging.publish.attempts";

    private SimpleMeterRegistry metricas;

    @BeforeEach
    void criarMetricas() {
        metricas = new SimpleMeterRegistry();
    }

    @AfterEach
    void fecharMetricas() {
        metricas.close();
    }

    @Test
    void publicar_deveEnviarContratoPersistente_quandoBrokerConfirmarSemRetorno() {
        var rabbitTemplate = mock(RabbitTemplate.class);
        var publicador = new RabbitMqPublicadorEvento(rabbitTemplate, metricas);
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
        var contador = metricas.find(METRICA).tag("outcome", "confirmed").counter();
        assertThat(contador).isNotNull();
        assertThat(contador.count()).isEqualTo(1.0);
        assertThat(metricas.getMeters()).hasSize(1);
        assertThat(contador.getId().getTags()).hasSize(1);
    }

    @Test
    void publicar_deveFalhar_quandoMensagemForRetornadaMesmoComAck() {
        var rabbitTemplate = mock(RabbitTemplate.class);
        var publicador = new RabbitMqPublicadorEvento(rabbitTemplate, metricas);
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
        var contador = metricas.find(METRICA).tag("outcome", "returned").counter();
        assertThat(contador).isNotNull();
        assertThat(contador.count()).isEqualTo(1.0);
        assertThat(metricas.find(METRICA).tag("outcome", "confirmed").counter()).isNull();
        assertThat(metricas.getMeters()).hasSize(1);
    }

    @Test
    void publicar_deveFalhar_quandoBrokerResponderNack() {
        var rabbitTemplate = mock(RabbitTemplate.class);
        var publicador = new RabbitMqPublicadorEvento(rabbitTemplate, metricas);
        doAnswer(invocacao -> {
            var correlacao = invocacao.getArgument(3, CorrelationData.class);
            correlacao.getFuture().complete(new CorrelationData.Confirm(false, "broker indisponível"));
            return null;
        }).when(rabbitTemplate).send(any(String.class), any(String.class), any(), any());

        var confirmado = publicador.publicar(evento());

        assertThat(confirmado).isFalse();
        var contador = metricas.find(METRICA).tag("outcome", "nacked").counter();
        assertThat(contador).isNotNull();
        assertThat(contador.count()).isEqualTo(1.0);
        assertThat(metricas.getMeters()).hasSize(1);
    }

    @Test
    void publicar_deveContarErroEPreservarExcecao_quandoEnvioFalharImediatamente() {
        var rabbitTemplate = mock(RabbitTemplate.class);
        var falha = new IllegalStateException("falha controlada no envio");
        doThrow(falha).when(rabbitTemplate).send(any(String.class), any(String.class), any(), any());
        var publicador = new RabbitMqPublicadorEvento(rabbitTemplate, metricas);

        assertThatThrownBy(() -> publicador.publicar(evento())).isSameAs(falha);

        var contador = metricas.find(METRICA).tag("outcome", "error").counter();
        assertThat(contador).isNotNull();
        assertThat(contador.count()).isEqualTo(1.0);
        assertThat(metricas.getMeters()).hasSize(1);
    }

    @Test
    void publicar_deveContarErroEPreservarCausa_quandoConfirmacaoFalhar() {
        var rabbitTemplate = mock(RabbitTemplate.class);
        var falha = new IllegalStateException("falha controlada na confirmação");
        doAnswer(invocacao -> {
            var correlacao = invocacao.getArgument(3, CorrelationData.class);
            correlacao.getFuture().completeExceptionally(falha);
            return null;
        }).when(rabbitTemplate).send(any(String.class), any(String.class), any(), any());
        var publicador = new RabbitMqPublicadorEvento(rabbitTemplate, metricas);

        assertThatThrownBy(() -> publicador.publicar(evento()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("não foi possível obter confirmação do RabbitMQ")
                .hasRootCause(falha);

        var contador = metricas.find(METRICA).tag("outcome", "error").counter();
        assertThat(contador).isNotNull();
        assertThat(contador.count()).isEqualTo(1.0);
        assertThat(metricas.getMeters()).hasSize(1);
    }

    @Test
    void publicar_deveContarErroEPreservarInterrupcao_quandoEsperaForInterrompida() {
        var rabbitTemplate = mock(RabbitTemplate.class);
        var publicador = new RabbitMqPublicadorEvento(rabbitTemplate, metricas);
        try {
            Thread.currentThread().interrupt();

            assertThatThrownBy(() -> publicador.publicar(evento()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("interrompido ao aguardar confirmação do RabbitMQ")
                    .hasCauseInstanceOf(InterruptedException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();

            var contador = metricas.find(METRICA).tag("outcome", "error").counter();
            assertThat(contador).isNotNull();
            assertThat(contador.count()).isEqualTo(1.0);
            assertThat(metricas.getMeters()).hasSize(1);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void publicar_deveContarErro_quandoConfirmacaoNaoChegarNoPrazo() {
        var rabbitTemplate = mock(RabbitTemplate.class);
        var publicador = new RabbitMqPublicadorEvento(rabbitTemplate, metricas);

        assertThatThrownBy(() -> publicador.publicar(evento()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("não foi possível obter confirmação do RabbitMQ")
                .hasCauseInstanceOf(TimeoutException.class);

        var contador = metricas.find(METRICA).tag("outcome", "error").counter();
        assertThat(contador).isNotNull();
        assertThat(contador.count()).isEqualTo(1.0);
        assertThat(metricas.getMeters()).hasSize(1);
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
