# CredPay — Tarefas

> Um incremento = um resultado pequeno e verificável. Coordenar pelos papéis em `docs/roles/`; manter prioridade/aceite em `docs/BACKLOG.md` e evidências em `spec.md`. Concluir revisão e verificações antes de avançar no ciclo.

## Último incremento

- B04.12 integrado na PR #93 (`ef31015`): políticas operacionais e reaplicação comprovadas no CI #147, com 114 testes; Secret Scan #12 verde no SHA final. B05.1 define contrato do retorno, causalidade, histórico atômico, replay e compatibilidade HTTP na spec 9.40. Warnings das fixtures continuam em B08.2.

## Agora

- [ ] B05.2 — Domínio final e reconstrução implementados em TDD: reds observados, 23 testes focados e 50 sem infraestrutura verdes. Aguardar suíte completa/PostgreSQL real e Secret Scan no CI antes de integrar. Sem listener, migration ou atualização persistente neste incremento; depois B05.3.
