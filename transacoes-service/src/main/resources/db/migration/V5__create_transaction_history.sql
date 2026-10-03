ALTER TABLE outbox_eventos
    ADD CONSTRAINT uq_outbox_evento_agregado UNIQUE (event_id, aggregate_id);

CREATE TABLE historico_transacoes (
    event_id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    event_version INTEGER NOT NULL,
    correlation_id UUID NOT NULL,
    causation_id UUID NOT NULL,
    estado_anterior VARCHAR(16) NOT NULL,
    estado_final VARCHAR(16) NOT NULL,
    origem VARCHAR(100) NOT NULL,
    occurred_at_epoch_second BIGINT NOT NULL,
    occurred_at_nano INTEGER NOT NULL,
    aplicado_em TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_historico_transacao UNIQUE (transaction_id),
    CONSTRAINT fk_historico_transacao FOREIGN KEY (transaction_id) REFERENCES transacoes (id),
    CONSTRAINT fk_historico_causa_transacao FOREIGN KEY (causation_id, transaction_id)
        REFERENCES outbox_eventos (event_id, aggregate_id),
    CONSTRAINT ck_historico_tipo_versao CHECK (event_type = 'TransacaoProcessada' AND event_version = 1),
    CONSTRAINT ck_historico_correlacao CHECK (correlation_id = transaction_id),
    CONSTRAINT ck_historico_estado_anterior CHECK (estado_anterior = 'PENDENTE'),
    CONSTRAINT ck_historico_estado_final CHECK (estado_final IN ('APROVADA', 'REJEITADA')),
    CONSTRAINT ck_historico_origem CHECK (origem = 'processamento-service/TransacaoProcessada.v1'),
    CONSTRAINT ck_historico_nano CHECK (occurred_at_nano BETWEEN 0 AND 999999999)
);
