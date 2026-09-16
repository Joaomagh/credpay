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
    public void inserir(UUID chaveIdempotencia, Transacao transacao) {
        entityManager.persist(new IdempotenciaTransacaoEntity(chaveIdempotencia, transacao));
    }

    @Override
    public void bloquearChaveIdempotencia(UUID chaveIdempotencia) {
        entityManager.createNativeQuery("""
                        SELECT 1
                        FROM pg_advisory_xact_lock(
                            hashtextextended(CAST(:chave AS text), 0))
                        """, Long.class)
                .setParameter("chave", chaveIdempotencia.toString())
                .getSingleResult();
    }

    @Override
    public Optional<Transacao> buscarPorId(UUID id) {
        return Optional.ofNullable(entityManager.find(TransacaoEntity.class, id))
                .map(TransacaoEntity::paraDominio);
    }

    @Override
    public Optional<Transacao> buscarPorChaveIdempotencia(UUID chaveIdempotencia) {
        return Optional.ofNullable(entityManager.find(
                        IdempotenciaTransacaoEntity.class, chaveIdempotencia))
                .map(IdempotenciaTransacaoEntity::paraDominio);
    }
}
