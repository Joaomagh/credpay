package br.com.credpay.transacoes.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import br.com.credpay.transacoes.application.ClienteRepository;
import br.com.credpay.transacoes.domain.Cliente;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class ClienteJdbcRepository implements ClienteRepository {

    private final JdbcTemplate jdbc;

    ClienteJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void inserir(Cliente cliente) {
    }

    @Override
    public Optional<Cliente> buscar(UUID contaId, UUID clienteId) {
        return Optional.empty();
    }

    @Override
    public List<Cliente> listar(UUID contaId, int limite, UUID apos) {
        return List.of();
    }
}
