package br.com.credpay.transacoes.infrastructure.persistence;

import java.util.UUID;
import br.com.credpay.transacoes.application.ContasEmail;
import org.springframework.stereotype.Repository;

@Repository
class ContasEmailJdbc implements ContasEmail {
    @Override
    public void cadastrar(UUID conta, String email, String senhaHash) {
        throw new UnsupportedOperationException("Persistência de e-mail ainda não implementada.");
    }
}
