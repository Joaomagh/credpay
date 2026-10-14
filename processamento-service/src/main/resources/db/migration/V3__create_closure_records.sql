CREATE TABLE encerramentos (
    request_event_id uuid NOT NULL,
    transaction_id uuid NOT NULL,
    creation_event_id uuid NOT NULL,
    event_type varchar(100) NOT NULL,
    event_version integer NOT NULL,
    request_occurred_epoch_second bigint NOT NULL,
    request_occurred_nano integer NOT NULL,
    correlation_id uuid NOT NULL,
    motivo varchar(64) NOT NULL,
    evidence_id uuid NOT NULL,
    resultado varchar(32) NOT NULL,
    response_event_id uuid NOT NULL,
    responded_at timestamptz(6) NOT NULL,
    output_event_id uuid,
    CONSTRAINT pk_encerramentos PRIMARY KEY (request_event_id),
    CONSTRAINT uq_encerramentos_transaction_id UNIQUE (transaction_id),
    CONSTRAINT uq_encerramentos_response_event_id UNIQUE (response_event_id),
    CONSTRAINT ck_encerramentos_event_type CHECK (event_type = 'EncerramentoTransacaoSolicitado'),
    CONSTRAINT ck_encerramentos_event_version CHECK (event_version = 1),
    CONSTRAINT ck_encerramentos_occurred_nano CHECK (request_occurred_nano BETWEEN 0 AND 999999999),
    CONSTRAINT ck_encerramentos_correlation CHECK (correlation_id = transaction_id),
    CONSTRAINT ck_encerramentos_motivo CHECK (motivo = 'FALHA_PERMANENTE_CONTROLADA'),
    CONSTRAINT ck_encerramentos_resultado_saida CHECK (
        (resultado = 'CONFIRMADO' AND output_event_id IS NULL)
        OR (resultado = 'DECISAO_EXISTENTE' AND output_event_id IS NOT NULL)
    )
);

CREATE TABLE outbox_encerramentos (
    event_id uuid NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type varchar(100) NOT NULL,
    event_version integer NOT NULL,
    payload jsonb NOT NULL,
    occurred_at timestamptz(6) NOT NULL,
    published_at timestamptz(6),
    CONSTRAINT pk_outbox_encerramentos PRIMARY KEY (event_id),
    CONSTRAINT uq_outbox_encerramentos_aggregate_id UNIQUE (aggregate_id),
    CONSTRAINT ck_outbox_encerramentos_event_type CHECK (event_type = 'EncerramentoTransacaoRespondido'),
    CONSTRAINT ck_outbox_encerramentos_event_version CHECK (event_version = 1),
    CONSTRAINT ck_outbox_encerramentos_payload_object CHECK (jsonb_typeof(payload) = 'object')
);
