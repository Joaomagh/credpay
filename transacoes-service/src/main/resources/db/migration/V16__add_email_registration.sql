ALTER TABLE contas ADD COLUMN email text;

ALTER TABLE contas
    ADD CONSTRAINT uq_contas_email UNIQUE (email),
    ADD CONSTRAINT ck_contas_email CHECK (
        email IS NULL OR (
            senha_hash IS NOT NULL
            AND char_length(email) BETWEEN 3 AND 254
            AND char_length(split_part(email, '@', 1)) <= 64
            AND email = lower(btrim(email))
            AND email ~ '^[a-z0-9!#$%&''*+/=?^_`{|}~-]+(\.[a-z0-9!#$%&''*+/=?^_`{|}~-]+)*@[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*\.[a-z]{2,63}$'
        )
    );
