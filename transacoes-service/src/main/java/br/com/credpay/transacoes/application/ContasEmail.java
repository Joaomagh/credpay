package br.com.credpay.transacoes.application;

import java.util.UUID;

public interface ContasEmail {
    void cadastrar(UUID conta, String email, String senhaHash);
}
