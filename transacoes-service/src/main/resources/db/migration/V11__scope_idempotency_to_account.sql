ALTER TABLE transacoes
    ADD CONSTRAINT uq_transacoes_id_conta UNIQUE (id, conta_id);

ALTER TABLE idempotencias_transacao
    ADD COLUMN conta_id uuid,
    DROP CONSTRAINT idempotencias_transacao_pkey,
    DROP CONSTRAINT uq_idempotencias_transacao_transacao,
    ALTER COLUMN chave SET NOT NULL,
    ADD CONSTRAINT idempotencias_transacao_pkey PRIMARY KEY (transacao_id),
    ADD CONSTRAINT uq_idempotencias_transacao_chave_conta UNIQUE NULLS NOT DISTINCT (chave, conta_id),
    ADD CONSTRAINT fk_idempotencias_transacao_conta
        FOREIGN KEY (transacao_id, conta_id) REFERENCES transacoes (id, conta_id);
