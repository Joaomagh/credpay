# CredPay — Tarefas

> Um incremento = um resultado pequeno e verificável. Coordenar pelos papéis em `docs/roles/`; manter prioridade/aceite em `docs/BACKLOG.md` e evidências em `spec.md`. Concluir revisão e verificações antes de avançar no ciclo.

## Último incremento

- B05.2 validado na PR #95: TDD de estados finais imutáveis e reconstrução; CI #110 verde com 108 testes e Secret Scan #17 verde. B04.12 e contrato B05.1 integrados nas PRs #93/#94. Evidências e limites na spec 9.39–9.41; warnings das fixtures do processador continuam em B08.2.

## Próximo

- [ ] B05.3 — Implementar em TDD a persistência atômica de estado final e histórico/recebimento: migration e adapters próprios, identidade/precisão preservadas, unicidade e rollback em PostgreSQL real. Sem listener ou ativação do fluxo neste incremento.
