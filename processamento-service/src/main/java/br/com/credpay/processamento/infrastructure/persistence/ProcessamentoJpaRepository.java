package br.com.credpay.processamento.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import br.com.credpay.processamento.application.ProcessamentoRegistrado;
import br.com.credpay.processamento.application.ProcessamentoRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

@Repository
class ProcessamentoJpaRepository implements ProcessamentoRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public void inserir(ProcessamentoRegistrado processamento) {
        entityManager.persist(new ProcessamentoEntity(processamento));
    }

    @Override
    public Optional<ProcessamentoRegistrado> buscarPorEventId(UUID eventId) {
        return Optional.ofNullable(entityManager.find(ProcessamentoEntity.class, eventId))
                .map(ProcessamentoEntity::paraAplicacao);
    }

    @Override
    public boolean existePorTransactionId(UUID transactionId) {
        return !entityManager.createQuery(
                "select processamento.eventId from ProcessamentoEntity processamento "
                        + "where processamento.transactionId = :transactionId",
                UUID.class)
                .setParameter("transactionId", transactionId)
                .setMaxResults(1)
                .getResultList()
                .isEmpty();
    }
}
