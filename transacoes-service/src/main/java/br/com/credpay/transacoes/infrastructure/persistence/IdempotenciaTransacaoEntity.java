package br.com.credpay.transacoes.infrastructure.persistence;

import java.util.UUID;

import br.com.credpay.transacoes.domain.Transacao;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "idempotencias_transacao")
class IdempotenciaTransacaoEntity {

    @Id
    @Column(name = "transacao_id")
    private UUID transacaoId;

    @Column(nullable = false)
    private UUID chave;

    @Column(name = "conta_id")
    private UUID contaId;

    @MapsId
    @OneToOne(optional = false, fetch = FetchType.LAZY, cascade = CascadeType.PERSIST)
    @JoinColumn(name = "transacao_id", nullable = false)
    private TransacaoEntity transacao;

    protected IdempotenciaTransacaoEntity() {
    }

    IdempotenciaTransacaoEntity(UUID contaId, UUID chave, Transacao transacao) {
        this.contaId = contaId;
        this.chave = chave;
        this.transacao = new TransacaoEntity(transacao, contaId);
    }

    Transacao paraDominio() {
        return transacao.paraDominio();
    }
}
