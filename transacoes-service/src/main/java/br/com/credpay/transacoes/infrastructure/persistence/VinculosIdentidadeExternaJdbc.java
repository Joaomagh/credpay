package br.com.credpay.transacoes.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import br.com.credpay.transacoes.application.VinculosIdentidadeExterna;
import br.com.credpay.transacoes.application.CadastroContaExterna;
import br.com.credpay.transacoes.domain.IdentidadeExterna;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

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
    public UUID obterOuCriar(IdentidadeExterna identidade) {
        return null;
    }
}
