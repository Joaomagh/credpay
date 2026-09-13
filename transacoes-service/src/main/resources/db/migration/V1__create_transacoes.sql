CREATE TABLE transacoes (
    id uuid PRIMARY KEY,
    valor numeric NOT NULL,
    moeda varchar(3) NOT NULL,
    status varchar(16) NOT NULL
);
