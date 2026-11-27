CREATE TABLE identidades_externas (
    emissor text NOT NULL,
    sujeito varchar(255) NOT NULL,
    conta_id uuid NOT NULL REFERENCES contas (id),
    PRIMARY KEY (emissor, sujeito),
    CONSTRAINT ck_identidades_emissor CHECK (btrim(emissor) <> ''),
    CONSTRAINT ck_identidades_sujeito CHECK (btrim(sujeito) <> '')
);
