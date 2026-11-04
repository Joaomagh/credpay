package br.com.credpay.transacoes.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import br.com.credpay.transacoes.application.TransacaoRepository;
import br.com.credpay.transacoes.domain.Transacao;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;

@Repository
class TransacaoJpaRepository implements TransacaoRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public void inserir(Transacao transacao) {
        entityManager.persist(new TransacaoEntity(transacao));
    }

    @Override
    public void inserir(UUID contaId, UUID chaveIdempotencia, Transacao transacao) {
        entityManager.persist(new IdempotenciaTransacaoEntity(contaId, chaveIdempotencia, transacao));
    }

    @Override
    public void bloquearChaveIdempotencia(UUID contaId, UUID chaveIdempotencia) {
        var escopo = contaId == null ? chaveIdempotencia.toString() : contaId + ":" + chaveIdempotencia;
        entityManager.createNativeQuery("""
                        SELECT 1
                        FROM pg_advisory_xact_lock(
                            hashtextextended(CAST(:chave AS text), 0))
                        """, Long.class)
                .setParameter("chave", escopo)
                .getSingleResult();
    }

    @Override
    public Optional<Transacao> buscarPorId(UUID id) {
        return Optional.ofNullable(entityManager.find(TransacaoEntity.class, id))
                .map(TransacaoEntity::paraDominio);
    }

    @Override
    public Optional<Transacao> buscarPorIdNaConta(UUID id, UUID contaId) {
        return Optional.ofNullable(entityManager.createQuery("""
                        SELECT t FROM TransacaoEntity t
                        WHERE t.id = :id AND t.contaId IS NOT DISTINCT FROM :contaId
                        """, TransacaoEntity.class)
                .setParameter("id", id)
                .setParameter("contaId", contaId)
                .getSingleResultOrNull()).map(TransacaoEntity::paraDominio);
    }

    @Override
    public Optional<Transacao> buscarPorChaveIdempotencia(UUID contaId, UUID chaveIdempotencia) {
        return Optional.ofNullable(entityManager.createQuery("""
                        SELECT i FROM IdempotenciaTransacaoEntity i JOIN FETCH i.transacao t
                        WHERE i.chave = :chave AND i.contaId IS NOT DISTINCT FROM :contaId
                          AND t.contaId IS NOT DISTINCT FROM :contaId
                        """, IdempotenciaTransacaoEntity.class)
                .setParameter("chave", chaveIdempotencia)
                .setParameter("contaId", contaId)
                .getSingleResultOrNull()).map(IdempotenciaTransacaoEntity::paraDominio);
    }
}
