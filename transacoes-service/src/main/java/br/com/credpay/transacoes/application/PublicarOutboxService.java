package br.com.credpay.transacoes.application;

import java.time.Clock;

import org.springframework.stereotype.Service;

@Service
public class PublicarOutboxService {

    private static final int TAMANHO_MAXIMO_LOTE = 20;

    private final OutboxRepository outboxRepository;
    private final PublicadorEvento publicadorEvento;
    private final Clock clock;

    public PublicarOutboxService(
            OutboxRepository outboxRepository,
            PublicadorEvento publicadorEvento,
            Clock clock) {
        this.outboxRepository = outboxRepository;
        this.publicadorEvento = publicadorEvento;
        this.clock = clock;
    }

    public boolean publicarProximo() {
        var pendentes = outboxRepository.buscarPendentes(1);
        if (pendentes.isEmpty()) {
            return false;
        }

        return publicarEMarcar(pendentes.getFirst());
    }

    public int publicarLote() {
        var publicados = 0;
        for (var evento : outboxRepository.buscarPendentes(TAMANHO_MAXIMO_LOTE)) {
            if (!publicarEMarcar(evento)) {
                break;
            }
            publicados++;
        }
        return publicados;
    }

    private boolean publicarEMarcar(EventoOutbox evento) {
        if (!publicadorEvento.publicar(evento)) {
            return false;
        }

        outboxRepository.marcarPublicado(evento.eventId(), clock.instant());
        return true;
    }
}
