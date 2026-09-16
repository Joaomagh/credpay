CREATE TABLE idempotencias_transacao (
    chave uuid PRIMARY KEY,
    transacao_id uuid NOT NULL,
    CONSTRAINT uq_idempotencias_transacao_transacao UNIQUE (transacao_id),
    CONSTRAINT fk_idempotencias_transacao_transacao
        FOREIGN KEY (transacao_id) REFERENCES transacoes (id)
);
