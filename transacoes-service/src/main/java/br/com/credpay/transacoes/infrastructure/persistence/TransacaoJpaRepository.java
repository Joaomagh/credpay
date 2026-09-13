package br.com.credpay.transacoes.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import br.com.credpay.transacoes.application.TransacaoRepository;
import br.com.credpay.transacoes.domain.Transacao;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

@Repository
@Profile("persistencia")
class TransacaoJpaRepository implements TransacaoRepository {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public void inserir(Transacao transacao) {
        entityManager.persist(new TransacaoEntity(transacao));
    }

    @Override
    public Optional<Transacao> buscarPorId(UUID id) {
        return Optional.ofNullable(entityManager.find(TransacaoEntity.class, id))
                .map(TransacaoEntity::paraDominio);
    }
}
