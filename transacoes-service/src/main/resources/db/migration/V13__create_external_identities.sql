CREATE TABLE identidades_externas (
    emissor text NOT NULL,
    sujeito varchar(255) NOT NULL,
    conta_id uuid NOT NULL REFERENCES contas (id),
    PRIMARY KEY (emissor, sujeito),
    CONSTRAINT ck_identidades_emissor CHECK (char_length(btrim(emissor)) > 0),
    CONSTRAINT ck_identidades_sujeito CHECK (char_length(btrim(sujeito)) > 0)
);
