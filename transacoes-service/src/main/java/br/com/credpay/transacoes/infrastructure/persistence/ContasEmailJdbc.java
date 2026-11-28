package br.com.credpay.transacoes.infrastructure.persistence;

import java.util.UUID;
import br.com.credpay.transacoes.application.ContasEmail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class ContasEmailJdbc implements ContasEmail {
    private final JdbcTemplate jdbc;

    ContasEmailJdbc(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public void cadastrar(UUID conta, String email, String senhaHash) {
        jdbc.update("""
                INSERT INTO contas (id, usuario, senha_hash, email) VALUES (?, ?, ?, ?)
                ON CONFLICT (email) DO NOTHING
                """, conta, "email-" + conta, senhaHash, email);
    }
}
