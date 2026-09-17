package br.com.credpay.transacoes.infrastructure.messaging;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import br.com.credpay.transacoes.application.EventoOutbox;
import br.com.credpay.transacoes.application.PublicadorEvento;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
class RabbitMqPublicadorEvento implements PublicadorEvento {

    private static final long CONFIRMACAO_TIMEOUT_SEGUNDOS = 5;

    private final RabbitTemplate rabbitTemplate;

    RabbitMqPublicadorEvento(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public boolean publicar(EventoOutbox evento) {
        var propriedades = new MessageProperties();
        propriedades.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        propriedades.setContentEncoding(StandardCharsets.UTF_8.name());
        propriedades.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        propriedades.setMessageId(evento.eventId().toString());
        propriedades.setType(evento.eventType());
        propriedades.setCorrelationId(evento.aggregateId().toString());
        var mensagem = new Message(
                evento.payload().getBytes(StandardCharsets.UTF_8),
                propriedades);
        var correlacao = new CorrelationData(evento.eventId().toString());

        rabbitTemplate.send(
                RabbitMqConfiguration.TRANSACAO_EVENTOS_EXCHANGE,
                RabbitMqConfiguration.TRANSACAO_CRIADA_ROUTING_KEY,
                mensagem,
                correlacao);

        try {
            var confirmacao = correlacao.getFuture().get(
                    CONFIRMACAO_TIMEOUT_SEGUNDOS,
                    TimeUnit.SECONDS);
            return confirmacao.isAck() && correlacao.getReturned() == null;
        } catch (InterruptedException excecao) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "interrompido ao aguardar confirmação do RabbitMQ",
                    excecao);
        } catch (ExecutionException | TimeoutException excecao) {
            throw new IllegalStateException(
                    "não foi possível obter confirmação do RabbitMQ",
                    excecao);
        }
    }
}
