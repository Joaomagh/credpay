CREATE TABLE contas (
    id uuid PRIMARY KEY,
    usuario varchar(64) NOT NULL UNIQUE,
    senha_hash varchar(96) NOT NULL,
    CONSTRAINT ck_contas_usuario CHECK (usuario ~ '^[a-z0-9][a-z0-9._-]{2,63}$'),
    CONSTRAINT ck_contas_senha_hash CHECK (senha_hash ~ '^[0-9a-f]{96}$')
);
