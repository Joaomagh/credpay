ALTER TABLE encerramentos ADD CONSTRAINT uq_encerramentos_positive_receipt
    UNIQUE (request_event_id, transaction_id, creation_event_id, resultado);

CREATE TABLE criacao_bloqueada_recebimentos (
    event_id uuid PRIMARY KEY,
    transaction_id uuid NOT NULL UNIQUE,
    event_type varchar(64) NOT NULL CHECK (event_type = 'TransacaoCriada'),
    event_version integer NOT NULL CHECK (event_version = 1),
    event_occurred_epoch_second bigint NOT NULL,
    event_occurred_nano integer NOT NULL CHECK (event_occurred_nano BETWEEN 0 AND 999999999),
    correlation_id uuid NOT NULL CHECK (correlation_id = transaction_id),
    input_status varchar(16) NOT NULL CHECK (input_status = 'PENDENTE'),
    valor numeric NOT NULL CONSTRAINT ck_blocked_creation_valor_positivo_finito CHECK (
        valor > 0 AND valor NOT IN ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)
    ),
    moeda varchar(3) NOT NULL CONSTRAINT ck_blocked_creation_moeda_formato CHECK (moeda ~ '^[A-Z]{3}$'),
    request_event_id uuid NOT NULL,
    closure_result varchar(32) NOT NULL CHECK (closure_result = 'CONFIRMADO'),
    CONSTRAINT fk_blocked_creation_positive_closure
        FOREIGN KEY (request_event_id, transaction_id, event_id, closure_result)
        REFERENCES encerramentos (request_event_id, transaction_id, creation_event_id, resultado)
);
