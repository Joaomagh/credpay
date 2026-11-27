package br.com.credpay.transacoes.application;

import java.util.UUID;

import br.com.credpay.transacoes.domain.IdentidadeExterna;

public interface CadastroContaExterna {
    UUID obterOuCriar(IdentidadeExterna identidade);
}
