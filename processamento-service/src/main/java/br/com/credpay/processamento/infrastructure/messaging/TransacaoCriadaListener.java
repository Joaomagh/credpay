package br.com.credpay.processamento.infrastructure.messaging;

import br.com.credpay.processamento.application.ConflitoProcessamentoException;
import br.com.credpay.processamento.application.RegistrarProcessamentoService;
import br.com.credpay.processamento.application.TransacaoCriadaRecebida;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "credpay.processamento.consumer",
        name = {"topology.enabled", "listener.enabled"}, havingValue = "true")
final class TransacaoCriadaListener {

    private final TransacaoCriadaMessageParser parser;
    private final RegistrarProcessamentoService registrar;

    TransacaoCriadaListener(ObjectMapper objectMapper, RegistrarProcessamentoService registrar) {
        this.parser = new TransacaoCriadaMessageParser(objectMapper);
        this.registrar = registrar;
    }

    @RabbitListener(queues = RabbitMqEntradaConfiguration.ENTRADA, ackMode = "AUTO", concurrency = "1",
            containerFactory = "transacaoCriadaListenerContainerFactory")
    void receber(Message message) {
        TransacaoCriadaRecebida entrada;
        try {
            entrada = parser.parsear(message);
        } catch (IllegalArgumentException exception) {
            throw new AmqpRejectAndDontRequeueException("TransacaoCriada invalida: " + exception.getMessage());
        }
        try {
            registrar.executar(entrada);
        } catch (ConflitoProcessamentoException exception) {
            throw new AmqpRejectAndDontRequeueException("TransacaoCriada com conflito de identidade");
        }
    }
}
