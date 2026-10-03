# CredPay — Tarefas

> Um incremento = um resultado pequeno e verificável. Coordenar pelos papéis em `docs/roles/`; manter prioridade/aceite em `docs/BACKLOG.md` e evidências em `spec.md`. Concluir revisão e verificações antes de avançar no ciclo.

## Último incremento

- B05.4a integrado na PR #97, commit acf6517: CI #122/Secret Scan #29 verdes no último SHA. Aplicação sequencial/replay/causa comprovados; evidências/limites na spec 9.43.

## Próximo

- [ ] B05.4b em execução — Teste de disputa equivalente compilado; observar red PostgreSQL no CI antes de implementar locks. Depois provar conflitos, liberação após rollback e ordem das chaves efetivas; sem listener.
