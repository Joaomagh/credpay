CREATE TABLE encerramentos (
    request_event_id uuid,
    transaction_id uuid,
    creation_event_id uuid,
    event_type varchar(100),
    event_version integer,
    request_occurred_epoch_second bigint,
    request_occurred_nano integer,
    correlation_id uuid,
    motivo varchar(64),
    evidence_id uuid,
    resultado varchar(32),
    response_event_id uuid,
    responded_at timestamptz(6),
    output_event_id uuid,
    CONSTRAINT pk_encerramentos PRIMARY KEY (request_event_id),
    CONSTRAINT uq_encerramentos_transaction_id UNIQUE (transaction_id),
    CONSTRAINT uq_encerramentos_response_event_id UNIQUE (response_event_id)
);

CREATE TABLE outbox_encerramentos (
    event_id uuid,
    aggregate_id uuid,
    event_type varchar(100),
    event_version integer,
    payload jsonb,
    occurred_at timestamptz(6),
    published_at timestamptz(6),
    CONSTRAINT pk_outbox_encerramentos PRIMARY KEY (event_id),
    CONSTRAINT uq_outbox_encerramentos_aggregate_id UNIQUE (aggregate_id)
);
