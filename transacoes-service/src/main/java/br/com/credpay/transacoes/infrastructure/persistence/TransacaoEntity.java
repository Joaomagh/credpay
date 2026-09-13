package br.com.credpay.transacoes.infrastructure.persistence;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

import br.com.credpay.transacoes.domain.StatusTransacao;
import br.com.credpay.transacoes.domain.Transacao;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "transacoes")
class TransacaoEntity {

    @Id
    private UUID id;

    @Column(nullable = false, columnDefinition = "numeric")
    private BigDecimal valor;

    @Column(nullable = false, length = 3)
    private String moeda;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private StatusTransacao status;

    protected TransacaoEntity() {
    }

    TransacaoEntity(Transacao transacao) {
        id = transacao.id();
        valor = transacao.valor();
        moeda = transacao.moeda().getCurrencyCode();
        status = transacao.status();
    }

    Transacao paraDominio() {
        return switch (status) {
            case PENDENTE -> Transacao.criar(id, valor, Currency.getInstance(moeda));
        };
    }
}
