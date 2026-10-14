CREATE TABLE pedidos_encerramento (
    event_id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL,
    creation_event_id UUID NOT NULL,
    occurred_epoch_second BIGINT NOT NULL,
    occurred_nano INTEGER NOT NULL,
    correlation_id UUID NOT NULL,
    motivo VARCHAR(100) NOT NULL,
    evidence_ref UUID NOT NULL,
    CONSTRAINT uq_pedido_encerramento_transacao UNIQUE (transaction_id),
    CONSTRAINT uq_pedido_encerramento_evento_transacao UNIQUE (event_id, transaction_id),
    CONSTRAINT fk_pedido_encerramento_transacao FOREIGN KEY (transaction_id) REFERENCES transacoes (id),
    CONSTRAINT fk_pedido_encerramento_criacao FOREIGN KEY (creation_event_id, transaction_id)
        REFERENCES outbox_eventos (event_id, aggregate_id),
    CONSTRAINT ck_pedido_encerramento_correlacao CHECK (correlation_id = transaction_id),
    CONSTRAINT ck_pedido_encerramento_motivo CHECK (motivo = 'FALHA_PERMANENTE_CONTROLADA'),
    CONSTRAINT ck_pedido_encerramento_nano CHECK (occurred_nano BETWEEN 0 AND 999999999)
);

CREATE TABLE outbox_pedidos_encerramento (
    event_id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    event_version INTEGER NOT NULL,
    payload JSONB NOT NULL,
    occurred_epoch_second BIGINT NOT NULL,
    occurred_nano INTEGER NOT NULL,
    published_at TIMESTAMPTZ,
    CONSTRAINT fk_outbox_pedido_encerramento FOREIGN KEY (event_id, aggregate_id)
        REFERENCES pedidos_encerramento (event_id, transaction_id),
    CONSTRAINT ck_outbox_pedido_encerramento_tipo CHECK (event_type = 'EncerramentoTransacaoSolicitado'
        AND event_version = 1),
    CONSTRAINT ck_outbox_pedido_encerramento_payload CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_outbox_pedido_encerramento_nano CHECK (occurred_nano BETWEEN 0 AND 999999999)
);
