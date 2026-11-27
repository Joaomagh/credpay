package br.com.credpay.transacoes.application;

import java.util.Optional;
import java.util.UUID;

import br.com.credpay.transacoes.domain.IdentidadeExterna;

public interface VinculosIdentidadeExterna {
    Optional<UUID> buscarConta(IdentidadeExterna identidade);
}
