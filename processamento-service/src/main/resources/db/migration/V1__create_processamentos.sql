CREATE TABLE processamentos (
    event_id uuid PRIMARY KEY,
    transaction_id uuid NOT NULL,
    event_type varchar(64) NOT NULL,
    event_version integer NOT NULL,
    event_occurred_epoch_second bigint NOT NULL,
    event_occurred_nano integer NOT NULL,
    correlation_id uuid NOT NULL,
    input_status varchar(16) NOT NULL,
    valor numeric NOT NULL,
    moeda varchar(3) NOT NULL,
    limite_aplicado numeric NOT NULL,
    resultado varchar(16) NOT NULL,
    processed_at timestamptz(6) NOT NULL,
    output_event_id uuid NOT NULL,
    CONSTRAINT uq_processamentos_transaction_id UNIQUE (transaction_id),
    CONSTRAINT uq_processamentos_output_event_id UNIQUE (output_event_id),
    CONSTRAINT ck_processamentos_event_type CHECK (event_type = 'TransacaoCriada'),
    CONSTRAINT ck_processamentos_event_version CHECK (event_version = 1),
    CONSTRAINT ck_processamentos_event_occurred_nano
        CHECK (event_occurred_nano BETWEEN 0 AND 999999999),
    CONSTRAINT ck_processamentos_correlation CHECK (correlation_id = transaction_id),
    CONSTRAINT ck_processamentos_input_status CHECK (input_status = 'PENDENTE'),
    CONSTRAINT ck_processamentos_valor_positivo_finito CHECK (
        valor > 0
        AND valor NOT IN ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)
    ),
    CONSTRAINT ck_processamentos_moeda_formato CHECK (moeda ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_processamentos_limite_positivo_finito CHECK (
        limite_aplicado > 0
        AND limite_aplicado NOT IN ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)
    ),
    CONSTRAINT ck_processamentos_resultado CHECK (resultado IN ('APROVADA', 'REJEITADA'))
);
