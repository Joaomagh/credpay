package br.com.credpay.transacoes.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import br.com.credpay.transacoes.application.CobrancaRepository;
import br.com.credpay.transacoes.domain.Cobranca;
import org.springframework.stereotype.Repository;

@Repository
class CobrancaJdbcRepository implements CobrancaRepository {
    @Override
    public void bloquearChave(UUID contaId, UUID chave) { }
    @Override
    public Optional<Cobranca> buscarPorChave(UUID contaId, UUID chave) { return Optional.empty(); }
    @Override
    public void inserir(UUID chave, Cobranca cobranca) { }
}
