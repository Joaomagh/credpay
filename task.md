# CredPay — Tarefas

> Um incremento = um resultado pequeno e verificável. Coordenar pelos papéis em `docs/roles/`; manter prioridade/aceite em `docs/BACKLOG.md` e evidências em `spec.md`. Concluir revisão e verificações antes de avançar no ciclo.

## Próximo

- [ ] B02.4 — Comprovar a integridade do resultado persistido: colisões de `eventId`, `transactionId` e `outputEventId` não sobrescrevem o registro original, e uma inserção revertida após `flush` não permanece no PostgreSQL; sem replay, concorrência, consumidor ou outbox.
