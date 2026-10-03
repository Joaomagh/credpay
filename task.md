# CredPay — Tarefas

> Um incremento = um resultado pequeno e verificável. Coordenar pelos papéis em `docs/roles/`; manter prioridade/aceite em `docs/BACKLOG.md` e evidências em `spec.md`. Concluir revisão e verificações antes de avançar no ciclo.

## Último incremento

- B05.3 validado na PR #96: estado/histórico atômicos, precisão, integridade e rollback real; CI #118 verde com 129 testes e Secret Scan #25 verde. Recusa tipada corrigida após falha do CI #117; evidências na spec 9.42. Checks do SHA final obrigatórios antes de integrar.

## Próximo

- [ ] B05.4a — Implementar em TDD aplicação sequencial idempotente do resultado e validação causal: primeiro evento, replay sem relógio/escrita, conflitos e desconhecidos com estado preservado. PostgreSQL real deve comprovar uma transição e causa local sem published_at; sem listener/concorrência neste slice.
