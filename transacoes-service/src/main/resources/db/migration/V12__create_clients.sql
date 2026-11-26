CREATE TABLE clientes (
    id uuid PRIMARY KEY,
    conta_id uuid NOT NULL REFERENCES contas (id),
    nome text NOT NULL,
    CONSTRAINT uk_clientes_conta_id UNIQUE (conta_id, id),
    CONSTRAINT ck_clientes_nome CHECK (
        char_length(nome) BETWEEN 1 AND 120
        AND nome = btrim(nome, U&'\0020\00A0\1680\2000\2001\2002\2003\2004\2005\2006\2007\2008\2009\200A\2028\2029\202F\205F\3000')
        AND nome !~ U&'[\0001-\001F\007F-\009F]'
    )
);
