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
    aplicado_em TIMESTAMPTZ NOT NULL
);
