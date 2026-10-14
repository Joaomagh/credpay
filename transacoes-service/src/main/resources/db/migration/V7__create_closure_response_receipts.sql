ALTER TABLE pedidos_encerramento
    ADD CONSTRAINT uq_pedido_encerramento_evento_transacao_criacao
        UNIQUE (event_id, transaction_id, creation_event_id);

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
    output_event_id UUID,
    CONSTRAINT uq_resposta_encerramento_pedido UNIQUE (causation_id),
    CONSTRAINT fk_resposta_encerramento_pedido FOREIGN KEY (causation_id, transaction_id, creation_event_id)
        REFERENCES pedidos_encerramento (event_id, transaction_id, creation_event_id),
    CONSTRAINT ck_resposta_encerramento_tipo CHECK (event_type = 'EncerramentoTransacaoRespondido'
        AND event_version = 1),
    CONSTRAINT ck_resposta_encerramento_correlacao CHECK (correlation_id = transaction_id),
    CONSTRAINT ck_resposta_encerramento_nano CHECK (occurred_nano BETWEEN 0 AND 999999999),
    CONSTRAINT ck_resposta_encerramento_resultado CHECK (
        (resultado = 'CONFIRMADO' AND output_event_id IS NULL)
        OR (resultado = 'DECISAO_EXISTENTE' AND output_event_id IS NOT NULL))
);
