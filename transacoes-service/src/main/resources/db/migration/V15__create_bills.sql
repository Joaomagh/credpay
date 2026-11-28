CREATE TABLE cobrancas (
    id uuid PRIMARY KEY,
    conta_id uuid NOT NULL REFERENCES contas (id),
    chave uuid NOT NULL,
    cliente_id uuid NOT NULL,
    descricao text NOT NULL,
    valor numeric NOT NULL,
    moeda text NOT NULL,
    vencimento date NOT NULL,
    criada_em timestamptz NOT NULL,
    CONSTRAINT uk_cobrancas_conta_chave UNIQUE (conta_id, chave),
    CONSTRAINT fk_cobrancas_cliente_conta FOREIGN KEY (conta_id, cliente_id) REFERENCES clientes (conta_id, id),
    CONSTRAINT ck_cobrancas_descricao CHECK (
        char_length(descricao) BETWEEN 1 AND 240
        AND descricao = btrim(descricao, U&'\0020\00A0\1680\2000\2001\2002\2003\2004\2005\2006\2007\2008\2009\200A\2028\2029\202F\205F\3000')
        AND descricao !~ U&'[\0001-\001F\007F-\009F]'
    ),
    CONSTRAINT ck_cobrancas_valor CHECK (
        valor > 0 AND valor < 100000000000000000
        AND valor NOT IN ('NaN'::numeric, 'Infinity'::numeric, '-Infinity'::numeric)
        AND scale(trim_scale(valor)) <= 2
    ),
    CONSTRAINT ck_cobrancas_moeda CHECK (moeda = 'BRL'),
    CONSTRAINT ck_cobrancas_vencimento CHECK (vencimento BETWEEN DATE '0001-01-01' AND DATE '9999-12-31')
);
