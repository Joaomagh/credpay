package br.com.credpay.transacoes.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import br.com.credpay.transacoes.application.VinculosIdentidadeExterna;
import br.com.credpay.transacoes.application.CadastroContaExterna;
import br.com.credpay.transacoes.domain.IdentidadeExterna;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
class VinculosIdentidadeExternaJdbc implements VinculosIdentidadeExterna, CadastroContaExterna {
    private final JdbcTemplate jdbc;

    VinculosIdentidadeExternaJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<UUID> buscarConta(IdentidadeExterna identidade) {
        return jdbc.query("SELECT conta_id FROM identidades_externas WHERE emissor = ? AND sujeito = ?",
                (rs, linha) -> rs.getObject("conta_id", UUID.class), identidade.emissor(), identidade.sujeito())
                .stream().findFirst();
    }

    @Override
    @Transactional
    public UUID obterOuCriar(IdentidadeExterna identidade) {
        var chave = "oidc:" + identidade.emissor() + ":" + identidade.sujeito();
        jdbc.queryForObject("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(CAST(? AS text), 0))",
                Long.class, chave);
        var existente = buscarConta(identidade);
        if (existente.isPresent()) {
            return existente.get();
        }
        var conta = UUID.randomUUID();
        jdbc.update("INSERT INTO contas (id, usuario, senha_hash) VALUES (?, ?, NULL)", conta, "google-" + conta);
        jdbc.update("INSERT INTO identidades_externas (emissor, sujeito, conta_id) VALUES (?, ?, ?)",
                identidade.emissor(), identidade.sujeito(), conta);
        return conta;
    }
}
