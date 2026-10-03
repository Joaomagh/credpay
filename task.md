# CredPay — Tarefas

> Um incremento = um resultado pequeno e verificável. Coordenar pelos papéis em `docs/roles/`; manter prioridade/aceite em `docs/BACKLOG.md` e evidências em `spec.md`. Concluir revisão e verificações antes de avançar no ciclo.

## Último incremento

- B05.5c3 validado na PR #104: CI #148/Secret Scan #55 verdes, 279 testes com retry/rollback/DLQ reais. Runbook atualizado para ativação manual conferida; integração exige CIs aplicáveis no SHA final. Spec 9.50.

## Próximo

- [ ] B05.5d1 — Após integrar c3, executar dois JARs reais com bancos próprios/broker no CI e comprovar POST → duas outboxes → GET APROVADA/REJEITADA. Sem saída fabricada; B05 só termina após duplicata/replay em d2.
