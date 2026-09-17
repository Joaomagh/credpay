package br.com.credpay.transacoes.infrastructure.messaging;

import br.com.credpay.transacoes.application.PublicarOutboxService;
import org.springframework.scheduling.annotation.Scheduled;

class OutboxPublisherScheduler {

    private final PublicarOutboxService service;

    OutboxPublisherScheduler(PublicarOutboxService service) {
        this.service = service;
    }

    @Scheduled(
            fixedDelayString = "${credpay.outbox.publisher.interval:PT1S}",
            initialDelayString = "${credpay.outbox.publisher.interval:PT1S}")
    void publicarPendencias() {
        service.publicarLote();
    }
}
