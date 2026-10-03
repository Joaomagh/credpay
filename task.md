# CredPay — Tarefas

> Um incremento = um resultado pequeno e verificável. Coordenar pelos papéis em `docs/roles/`; manter prioridade/aceite em `docs/BACKLOG.md` e evidências em `spec.md`. Concluir revisão e verificações antes de avançar no ciclo.

## Último incremento

- B04.9 em validação: red observado e retry mínimo implementado; 75 testes sem infraestrutura verdes, prova de rollback/recuperação/esgotamento aguarda CI real. B04.8 integrado no PR #88. B08.1 segue pendente, sem evidência de vazamento na busca limitada realizada.

## Próximo

- [ ] B04.9 — Provar retry operacional limitado a três tentativas (esperas de 1 e 2 segundos), com recuperação ou DLQ ao esgotar e sem retry para erros permanentes.
