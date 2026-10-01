CREATE TABLE outbox_eventos (
    event_id uuid PRIMARY KEY REFERENCES processamentos (output_event_id)
        DEFERRABLE INITIALLY DEFERRED,
    aggregate_id uuid NOT NULL UNIQUE,
    event_type varchar(100) NOT NULL,
    event_version integer NOT NULL,
    payload jsonb NOT NULL,
    occurred_at timestamptz(6) NOT NULL,
    published_at timestamptz(6),
    CONSTRAINT ck_outbox_event_type CHECK (event_type = 'TransacaoProcessada'),
    CONSTRAINT ck_outbox_event_version CHECK (event_version = 1),
    CONSTRAINT ck_outbox_payload_object CHECK (jsonb_typeof(payload) = 'object')
);

INSERT INTO outbox_eventos (
    event_id, aggregate_id, event_type, event_version, payload, occurred_at
)
SELECT
    output_event_id,
    transaction_id,
    'TransacaoProcessada',
    1,
    jsonb_build_object(
        'eventId', output_event_id,
        'eventType', 'TransacaoProcessada',
        'eventVersion', 1,
        'occurredAt', to_char(processed_at AT TIME ZONE 'UTC',
                'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
        'correlationId', transaction_id,
        'causationId', event_id,
        'data', jsonb_build_object(
            'transactionId', transaction_id,
            'status', resultado
        )
    ),
    processed_at
FROM processamentos;
