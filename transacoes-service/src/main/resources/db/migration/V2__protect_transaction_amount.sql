ALTER TABLE transacoes
    ADD CONSTRAINT ck_transacoes_valor_positivo_finito
    CHECK (
        valor > 0
        AND valor NOT IN ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)
    );
