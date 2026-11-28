package br.com.credpay.transacoes.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import br.com.credpay.transacoes.application.CobrancaRepository;
import br.com.credpay.transacoes.domain.Cobranca;
import br.com.credpay.transacoes.domain.PedidoCobranca;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
class CobrancaJdbcRepository implements CobrancaRepository {
    private static final RowMapper<Cobranca> COBRANCA = (rs, linha) -> new Cobranca(
            rs.getObject("id", UUID.class), rs.getObject("conta_id", UUID.class),
            new PedidoCobranca(rs.getObject("cliente_id", UUID.class), rs.getString("descricao"),
                    rs.getBigDecimal("valor"), rs.getString("moeda"), rs.getObject("vencimento", LocalDate.class)),
            rs.getObject("criada_em", OffsetDateTime.class).toInstant());
    private final JdbcTemplate jdbc;

    CobrancaJdbcRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void bloquearChave(UUID contaId, UUID chave) {
        jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(CAST(? AS text), 0))",
                Long.class, "cobranca:" + contaId + ":" + chave);
    }
    @Override
    public Optional<Cobranca> buscarPorChave(UUID contaId, UUID chave) {
        return jdbc.query("""
                SELECT id, conta_id, cliente_id, descricao, valor, moeda, vencimento, criada_em
                FROM cobrancas WHERE conta_id = ? AND chave = ?
                """, COBRANCA, contaId, chave).stream().findFirst();
    }
    @Override
    public void inserir(UUID chave, Cobranca cobranca) {
        var pedido = cobranca.pedido();
        jdbc.update("""
                INSERT INTO cobrancas (id, conta_id, chave, cliente_id, descricao, valor, moeda, vencimento, criada_em)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, cobranca.id(), cobranca.contaId(), chave, pedido.clienteId(), pedido.descricao(), pedido.valor(),
                pedido.moeda(), pedido.vencimento(), OffsetDateTime.ofInstant(cobranca.criadaEm(), ZoneOffset.UTC));
    }
}
