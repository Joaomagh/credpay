# CredPay — Tarefas

> Um incremento = um resultado pequeno e verificável. Coordenar pelos papéis em `docs/roles/`; manter prioridade/aceite em `docs/BACKLOG.md` e evidências em `spec.md`. Concluir revisão e verificações antes de avançar no ciclo.

## Último incremento

- B05.5c2 integrado na PR #103 (34ba238), após CI #146/Secret Scan #53 verdes no SHA final. Rejeições reais para DLQ e banco intacto comprovados; spec 9.49.

## Próximo

- [ ] B05.5c3 — Em execução: red/green de três tentativas com esperas 1/2 s e permanentes sem retry observado. Provar rollback/recuperação e esgotamento para DLQ no CI, revisar e atualizar runbook somente após aceite real.
