package br.com.credpay.transacoes.application;

import br.com.credpay.transacoes.domain.Cliente;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ClienteRepository {

    void inserir(Cliente cliente);

    Optional<Cliente> buscar(UUID contaId, UUID clienteId);

    List<Cliente> listar(UUID contaId, int limite, UUID apos);
}
