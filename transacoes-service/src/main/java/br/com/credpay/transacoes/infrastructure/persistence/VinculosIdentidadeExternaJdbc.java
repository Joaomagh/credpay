package br.com.credpay.transacoes.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import br.com.credpay.transacoes.application.VinculosIdentidadeExterna;
import br.com.credpay.transacoes.domain.IdentidadeExterna;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class VinculosIdentidadeExternaJdbc implements VinculosIdentidadeExterna {
    VinculosIdentidadeExternaJdbc(JdbcTemplate jdbc) {
    }

    @Override
    public Optional<UUID> buscarConta(IdentidadeExterna identidade) {
        return Optional.empty();
    }
}
