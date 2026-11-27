package br.com.credpay.transacoes.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import br.com.credpay.transacoes.application.ClienteRepository;
import br.com.credpay.transacoes.domain.Cliente;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
class ClienteJdbcRepository implements ClienteRepository {

    private static final RowMapper<Cliente> CLIENTE = (rs, linha) -> new Cliente(
            rs.getObject("id", UUID.class), rs.getObject("conta_id", UUID.class), rs.getString("nome"));

    private final JdbcTemplate jdbc;

    ClienteJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void inserir(Cliente cliente) {
        jdbc.update("INSERT INTO clientes (id, conta_id, nome) VALUES (?, ?, ?)",
                cliente.id(), cliente.contaId(), cliente.nome());
    }

    @Override
    public Optional<Cliente> buscar(UUID contaId, UUID clienteId) {
        return jdbc.query("SELECT id, conta_id, nome FROM clientes WHERE conta_id = ? AND id = ?",
                CLIENTE, contaId, clienteId).stream().findFirst();
    }

    @Override
    public List<Cliente> listar(UUID contaId, int limite, UUID apos) {
        if (apos == null) {
            return jdbc.query("""
                    SELECT id, conta_id, nome FROM clientes WHERE conta_id = ? ORDER BY id LIMIT ?
                    """, CLIENTE, contaId, limite);
        }
        return jdbc.query("""
                SELECT id, conta_id, nome FROM clientes WHERE conta_id = ? AND id > ? ORDER BY id LIMIT ?
                """, CLIENTE, contaId, apos, limite);
    }
}
