package br.com.credpay.processamento.application;

import java.time.Clock;

import org.springframework.stereotype.Service;

@Service
public class PublicarOutboxProcessamentoService implements PublicarOutboxProcessamento {

    private final OutboxProcessamentoRepository outbox;
    private final PublicadorEventoSaida publicador;
    private final Clock clock;

    public PublicarOutboxProcessamentoService(
            OutboxProcessamentoRepository outbox,
            PublicadorEventoSaida publicador,
            Clock clock) {
        this.outbox = outbox;
        this.publicador = publicador;
        this.clock = clock;
    }

    @Override
    public boolean publicarProximo() {
        var pendentes = outbox.buscarPendentes(1);
        if (pendentes.isEmpty()) {
            return false;
        }

        var evento = pendentes.getFirst();
        if (!publicador.publicar(evento)) {
            return false;
        }

        outbox.marcarPublicado(evento.eventId(), clock.instant());
        return true;
    }
}
