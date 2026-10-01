# CredPay — Tarefas

> Um incremento = um resultado pequeno e verificável. Coordenar pelos papéis em `docs/roles/`; manter prioridade/aceite em `docs/BACKLOG.md` e evidências em `spec.md`. Concluir revisão e verificações antes de avançar no ciclo.

## Próximo

- [ ] B02.5 — Implementar idempotência sequencial na aplicação: persistir a primeira decisão, devolver o snapshot original em reentrega equivalente sem consultar política/relógio/gerador e rejeitar entradas conflitantes sem sobrescrita; sem concorrência, consumidor ou outbox.
