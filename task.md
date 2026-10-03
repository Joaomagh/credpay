# CredPay — Tarefas

> Um incremento = um resultado pequeno e verificável. Coordenar pelos papéis em `docs/roles/`; manter prioridade/aceite em `docs/BACKLOG.md` e evidências em `spec.md`. Concluir revisão e verificações antes de avançar no ciclo.

## Último incremento

- B05.4a validado na PR #97: CI #121 verde com 162 testes e Secret Scan #28 verde. Aplicação sequencial, replay semântico e causalidade comprovados; evidências/limites na spec 9.43. Checks do último SHA obrigatórios antes do merge.

## Próximo

- [ ] B05.4b — Implementar em TDD serialização por eventId/transactionId em READ_COMMITTED. Provar convergência equivalente, conflito sem sobrescrita, recuperação após rollback e ordem das chaves efetivas de advisory lock com PostgreSQL real; sem listener.
