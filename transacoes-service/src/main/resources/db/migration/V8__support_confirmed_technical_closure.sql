ALTER TABLE respostas_encerramento_recebidas
    ADD CONSTRAINT uq_resposta_encerramento_historico
        UNIQUE (event_id, transaction_id, causation_id, resultado);

ALTER TABLE historico_transacoes
    ADD COLUMN causa_criacao_id UUID GENERATED ALWAYS AS (
        CASE WHEN event_type = 'TransacaoProcessada' THEN causation_id END) STORED,
    ADD COLUMN causa_pedido_id UUID GENERATED ALWAYS AS (
        CASE WHEN event_type = 'EncerramentoTransacaoRespondido' THEN causation_id END) STORED,
    ADD COLUMN resultado_resposta VARCHAR(32) GENERATED ALWAYS AS (
        CASE WHEN event_type = 'EncerramentoTransacaoRespondido' THEN 'CONFIRMADO' END) STORED;

ALTER TABLE historico_transacoes
    DROP CONSTRAINT fk_historico_causa_transacao,
    DROP CONSTRAINT ck_historico_tipo_versao,
    DROP CONSTRAINT ck_historico_estado_final,
    DROP CONSTRAINT ck_historico_origem,
    ADD CONSTRAINT ck_historico_tipo_estado_origem CHECK (
        (event_type = 'TransacaoProcessada' AND event_version = 1
         AND estado_final IN ('APROVADA', 'REJEITADA')
         AND origem = 'processamento-service/TransacaoProcessada.v1')
        OR (event_type = 'EncerramentoTransacaoRespondido' AND event_version = 1
            AND estado_final = 'FALHOU'
            AND origem = 'processamento-service/EncerramentoTransacaoRespondido.v1')),
    ADD CONSTRAINT fk_historico_causa_criacao FOREIGN KEY (causa_criacao_id, transaction_id)
        REFERENCES outbox_eventos (event_id, aggregate_id),
    ADD CONSTRAINT fk_historico_causa_pedido FOREIGN KEY (causa_pedido_id, transaction_id)
        REFERENCES pedidos_encerramento (event_id, transaction_id),
    ADD CONSTRAINT fk_historico_resposta_confirmada
        FOREIGN KEY (event_id, transaction_id, causation_id, resultado_resposta)
        REFERENCES respostas_encerramento_recebidas (event_id, transaction_id, causation_id, resultado);
