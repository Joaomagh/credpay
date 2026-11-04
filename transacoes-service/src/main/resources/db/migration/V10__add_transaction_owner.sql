ALTER TABLE transacoes ADD COLUMN conta_id uuid REFERENCES contas (id);
