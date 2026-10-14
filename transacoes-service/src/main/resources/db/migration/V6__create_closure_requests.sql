CREATE TABLE pedidos_encerramento (
    event_id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL,
    creation_event_id UUID NOT NULL,
    occurred_epoch_second BIGINT NOT NULL,
    occurred_nano INTEGER NOT NULL,
    correlation_id UUID NOT NULL,
    motivo VARCHAR(100) NOT NULL,
    evidence_ref UUID NOT NULL
);

CREATE TABLE outbox_pedidos_encerramento (
    event_id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    event_version INTEGER NOT NULL,
    payload JSONB NOT NULL,
    occurred_epoch_second BIGINT NOT NULL,
    occurred_nano INTEGER NOT NULL,
    published_at TIMESTAMPTZ
);
