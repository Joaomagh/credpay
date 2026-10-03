package br.com.credpay.transacoes.infrastructure.messaging;

import br.com.credpay.transacoes.application.AplicarResultadoService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
        aplicar.executar(parser.parsear(message));
    }
}
