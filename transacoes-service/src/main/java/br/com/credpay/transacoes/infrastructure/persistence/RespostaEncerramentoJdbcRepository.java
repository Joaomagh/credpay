package br.com.credpay.transacoes.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import br.com.credpay.transacoes.application.RespostaEncerramentoRecebida;
import br.com.credpay.transacoes.application.RespostaEncerramentoRepository;
import org.springframework.stereotype.Repository;

@Repository
class RespostaEncerramentoJdbcRepository implements RespostaEncerramentoRepository {
    @Override public void inserir(RespostaEncerramentoRecebida resposta) {
        // Scaffolding TDD; nenhuma resposta persistida.
    }

    @Override public Optional<RespostaEncerramentoRecebida> buscarPorEvento(UUID eventId) {
        return Optional.empty();
    }
}
