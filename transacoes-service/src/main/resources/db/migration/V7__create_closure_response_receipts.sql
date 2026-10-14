CREATE TABLE respostas_encerramento_recebidas (
    event_id UUID PRIMARY KEY,
    transaction_id UUID NOT NULL,
    creation_event_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    event_version INTEGER NOT NULL,
    correlation_id UUID NOT NULL,
    causation_id UUID NOT NULL,
    occurred_epoch_second BIGINT NOT NULL,
    occurred_nano INTEGER NOT NULL,
    resultado VARCHAR(32) NOT NULL,
    output_event_id UUID
);
