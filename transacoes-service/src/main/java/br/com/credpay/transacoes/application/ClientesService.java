package br.com.credpay.transacoes.application;

import java.util.UUID;

import br.com.credpay.transacoes.domain.Cliente;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

@Service
class ClientesService implements Clientes {

    private final ClienteRepository repository;

    ClientesService(ClienteRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public Resultado cadastrar(UUID contaId, String nome) {
        var cliente = new Cliente(UUID.randomUUID(), contaId, nome);
        repository.inserir(cliente);
        return new Resultado(cliente.id(), cliente.nome());
    }

    @Override
    @Transactional(readOnly = true)
    public Resultado buscar(UUID contaId, UUID clienteId) {
        exigirConta(contaId);
        return repository.buscar(contaId, clienteId).map(this::resultado)
                .orElseThrow(ClienteNaoEncontradoException::new);
    }

    @Override
    @Transactional(readOnly = true)
    public Pagina listar(UUID contaId, int limite, UUID apos) {
        exigirConta(contaId);
        if (limite < 1 || limite > 50) {
            throw new IllegalArgumentException("limite deve estar entre 1 e 50");
        }
        var encontrados = repository.listar(contaId, limite + 1, apos);
        var itens = encontrados.subList(0, Math.min(limite, encontrados.size()));
        var proximo = encontrados.size() > limite ? itens.getLast().id() : null;
        return new Pagina(itens.stream().map(this::resultado).toList(), proximo);
    }

    private void exigirConta(UUID contaId) {
        if (contaId == null) {
            throw new IllegalArgumentException("conta deve ser informada");
        }
    }

    private Resultado resultado(Cliente cliente) {
        return new Resultado(cliente.id(), cliente.nome());
    }
}
