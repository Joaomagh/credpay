# CredPay — Tarefas

> Um incremento = um resultado pequeno e verificável. Coordenar pelos papéis em `docs/roles/`; manter prioridade/aceite em `docs/BACKLOG.md` e evidências em `spec.md`. Concluir revisão e verificações antes de avançar no ciclo.

## Último incremento

- B05.5d1 validado no Application Flow CI #1 da PR #105: dois estados finais pelos dois JARs reais, duas outboxes publicadas e causalidade/bancos próprios comprovados. Integração depende de suíte afetada/checks finais; spec 9.51.

## Próximo

- [ ] B05.5d2 — Após integrar d1, republicar eventos reais da outbox e comprovar ack/replay, registros completos intactos/histórico único e POST original PENDENTE enquanto GET permanece final.
