package br.com.credpay.processamento.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

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
    public void bloquearIdentidades(UUID eventId, UUID transactionId) {
        Stream.of(eventId, transactionId).map(this::chaveDeLock)
                .distinct().sorted().forEach(this::bloquear);
    }

    private Long chaveDeLock(UUID identidade) {
        return (Long) entityManager.createNativeQuery("""
                        SELECT hashtextextended(CAST(:identidade AS text), 0)
                        """, Long.class)
                .setParameter("identidade", identidade.toString())
                .getSingleResult();
    }

    private void bloquear(Long chave) {
        entityManager.createNativeQuery("""
                        SELECT 1
                        FROM pg_advisory_xact_lock(CAST(:chave AS bigint))
                        """, Long.class)
                .setParameter("chave", chave)
                .getSingleResult();
    }

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
