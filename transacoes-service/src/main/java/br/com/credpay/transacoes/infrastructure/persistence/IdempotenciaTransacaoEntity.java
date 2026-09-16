package br.com.credpay.transacoes.infrastructure.persistence;

import java.util.UUID;

import br.com.credpay.transacoes.domain.Transacao;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "idempotencias_transacao")
class IdempotenciaTransacaoEntity {

    @Id
    @Column(nullable = false)
    private UUID chave;

    @OneToOne(optional = false, fetch = FetchType.LAZY, cascade = CascadeType.PERSIST)
    @JoinColumn(name = "transacao_id", nullable = false, unique = true)
    private TransacaoEntity transacao;

    protected IdempotenciaTransacaoEntity() {
    }

    IdempotenciaTransacaoEntity(UUID chave, Transacao transacao) {
        this.chave = chave;
        this.transacao = new TransacaoEntity(transacao);
    }

    Transacao paraDominio() {
        return transacao.paraDominio();
    }
}
