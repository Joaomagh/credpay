package br.com.credpay.transacoes.domain;

public record IdentidadeExterna(String emissor, String sujeito) {
    public IdentidadeExterna {
        if (emissor == null || emissor.isBlank() || sujeito == null || sujeito.isBlank()) {
            throw new IllegalArgumentException("Identidade externa inválida");
        }
    }
}
