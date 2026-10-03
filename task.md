# CredPay — Tarefas

> Um incremento = um resultado pequeno e verificável. Coordenar pelos papéis em `docs/roles/`; manter prioridade/aceite em `docs/BACKLOG.md` e evidências em `spec.md`. Concluir revisão e verificações antes de avançar no ciclo.

## Último incremento

- B05.2 validado na PR #95: TDD de estados finais imutáveis e reconstrução; CI #110 verde com 108 testes e Secret Scan #17 verde. B04.12 e contrato B05.1 integrados nas PRs #93/#94. Evidências e limites na spec 9.39–9.41; warnings das fixtures do processador continuam em B08.2.

## Próximo

- [ ] B05.3 em execução — Teste de commit/releitura escrito; porta e record mínimos compilam após red de API ausente. Observar red de integração no CI antes do adapter/migration, pois Docker local está indisponível. Depois provar constraints e rollback real da segunda escrita; sem listener.
