package br.com.credpay.processamento.infrastructure.messaging;

import br.com.credpay.processamento.application.PublicarOutboxProcessamento;
import org.springframework.scheduling.annotation.Scheduled;

class OutboxPublisherScheduler {

    private final PublicarOutboxProcessamento publicador;

    OutboxPublisherScheduler(PublicarOutboxProcessamento publicador) {
        this.publicador = publicador;
    }

    @Scheduled(
            fixedDelayString = "${credpay.outbox.publisher.interval:PT1S}",
            initialDelayString = "${credpay.outbox.publisher.interval:PT1S}")
    void publicarPendencias() {
        publicador.publicarProximo();
    }
}
