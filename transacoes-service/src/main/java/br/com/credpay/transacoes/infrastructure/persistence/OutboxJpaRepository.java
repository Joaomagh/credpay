package br.com.credpay.transacoes.infrastructure.persistence;

import br.com.credpay.transacoes.application.EventoOutbox;
import br.com.credpay.transacoes.application.OutboxRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

@Repository
class OutboxJpaRepository implements OutboxRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public void adicionar(EventoOutbox evento) {
        entityManager.createNativeQuery("""
                        INSERT INTO outbox_eventos (
                            event_id,
                            aggregate_id,
                            event_type,
                            event_version,
                            payload,
                            occurred_at
                        ) VALUES (
                            :eventId,
                            :aggregateId,
                            :eventType,
                            :eventVersion,
                            CAST(:payload AS jsonb),
                            :occurredAt
                        )
                        """)
                .setParameter("eventId", evento.eventId())
                .setParameter("aggregateId", evento.aggregateId())
                .setParameter("eventType", evento.eventType())
                .setParameter("eventVersion", evento.eventVersion())
                .setParameter("payload", evento.payload())
                .setParameter("occurredAt", evento.occurredAt())
                .executeUpdate();
    }
}
