package br.com.credpay.transacoes.application;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import br.com.credpay.transacoes.domain.Cobranca;
import br.com.credpay.transacoes.domain.PedidoCobranca;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CobrancasService implements Cobrancas {
    private final CobrancaRepository repository;
    private final ClienteRepository clientes;
    private final Clock relogio;

    CobrancasService(CobrancaRepository repository, ClienteRepository clientes, @Qualifier("clock") Clock relogio) {
        this.repository = repository;
        this.clientes = clientes;
        this.relogio = relogio;
    }

    @Override
    @Transactional
    public Cobranca criar(UUID contaId, UUID chave, PedidoCobranca pedido) {
        if (contaId == null || chave == null || pedido == null) {
            throw new IllegalArgumentException("conta, chave e pedido devem ser informados");
        }
        repository.bloquearChave(contaId, chave);
        var existente = repository.buscarPorChave(contaId, chave);
        if (existente.isPresent()) {
            var original = existente.orElseThrow();
            if (!original.pedido().equals(pedido)) { throw new ConflitoIdempotenciaException(); }
            return original;
        }
        clientes.buscar(contaId, pedido.clienteId()).orElseThrow(ClienteNaoEncontradoException::new);
        var cobranca = new Cobranca(UUID.randomUUID(), contaId, pedido, relogio.instant().truncatedTo(ChronoUnit.MICROS));
        repository.inserir(chave, cobranca);
        return cobranca;
    }
}
