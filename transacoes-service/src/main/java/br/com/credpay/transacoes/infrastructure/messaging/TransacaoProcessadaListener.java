package br.com.credpay.transacoes.infrastructure.messaging;

import br.com.credpay.transacoes.application.AplicarResultadoService;
import br.com.credpay.transacoes.application.ConflitoResultadoException;
import br.com.credpay.transacoes.application.TransacaoProcessadaRecebida;
import br.com.credpay.transacoes.application.TransicaoRecusadaException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "credpay.transacoes.consumer",
        name = {"topology.enabled", "listener.enabled"}, havingValue = "true")
final class TransacaoProcessadaListener {

    private final TransacaoProcessadaMessageParser parser;
    private final AplicarResultadoService aplicar;

    TransacaoProcessadaListener(ObjectMapper mapper, AplicarResultadoService aplicar) {
        this.parser = new TransacaoProcessadaMessageParser(mapper);
        this.aplicar = aplicar;
    }

    @RabbitListener(queues = RabbitMqResultadoConfiguration.ENTRADA, ackMode = "AUTO", concurrency = "1",
            containerFactory = "transacaoProcessadaListenerContainerFactory")
    void receber(Message message) {
        TransacaoProcessadaRecebida entrada;
        try {
            entrada = parser.parsear(message);
        } catch (IllegalArgumentException exception) {
            throw new AmqpRejectAndDontRequeueException("TransacaoProcessada inválida: " + exception.getMessage());
        }
        try {
            aplicar.executar(entrada);
        } catch (ConflitoResultadoException exception) {
            throw new AmqpRejectAndDontRequeueException("TransacaoProcessada com conflito de identidade");
        } catch (TransicaoRecusadaException exception) {
            throw new AmqpRejectAndDontRequeueException("TransacaoProcessada recusada por transação ou causa inválida");
        }
    }
}
