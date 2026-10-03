# CredPay — Tarefas

> Um incremento = um resultado pequeno e verificável. Coordenar pelos papéis em `docs/roles/`; manter prioridade/aceite em `docs/BACKLOG.md` e evidências em `spec.md`. Concluir revisão e verificações antes de avançar no ciclo.

## Último incremento

- B06.1 integrado na PR #107 (`6665fef`): Flow #8/transações #159/Scan #66 verdes no SHA final `755bbd4`. Parada/reinício recuperam a causa original; spec 9.53.

## Agora

- [ ] B06.2 — Harness de DLQ/replay após correção de limite compilado offline e revisado sem bloqueante; CI real pendente, spec 9.54. Confirm/return precedem ack da original.

## Próximo

- [ ] B08.2a — Após integrar B06.2, comparação causal de uma fixture/pool/container no mesmo par/ordem antes/depois; sem silenciar warnings. FALHOU aguarda decisão do Navigator.
