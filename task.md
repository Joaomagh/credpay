# CredPay — Tarefas

> Um incremento = um resultado pequeno e verificável. Coordenar pelos papéis em `docs/roles/`; manter prioridade/aceite em `docs/BACKLOG.md` e evidências em `spec.md`. Concluir revisão e verificações antes de avançar no ciclo.

## Último incremento

- B05.5c2 validado na PR #103: CI #145/Secret Scan #52 verdes, 273 testes com rejeições reais para DLQ e banco intacto. Revisão sem bloqueante; integração depende de checks no SHA final. Ativação operacional proibida até c3; spec 9.49.

## Próximo

- [ ] B05.5c3 — Após integrar c2, limitar falhas operacionais a três tentativas com esperas 1/2 s; provar rollback/recuperação, esgotamento para DLQ e permanentes sem retry. Atualizar runbook somente após aceite real.
