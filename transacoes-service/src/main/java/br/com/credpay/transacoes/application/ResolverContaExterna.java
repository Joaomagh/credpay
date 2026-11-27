package br.com.credpay.transacoes.application;

import java.util.UUID;

import br.com.credpay.transacoes.domain.IdentidadeExterna;

public final class ResolverContaExterna {
    private final VinculosIdentidadeExterna vinculos;

    public ResolverContaExterna(VinculosIdentidadeExterna vinculos) {
        this.vinculos = vinculos;
    }

    public UUID resolver(IdentidadeExterna identidade) {
        return vinculos.buscarConta(identidade)
                .orElseThrow(() -> new SecurityException("Identidade externa sem vínculo"));
    }
}
