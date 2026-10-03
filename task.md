# CredPay — Tarefas

> Um incremento = um resultado pequeno e verificável. Coordenar pelos papéis em `docs/roles/`; manter prioridade/aceite em `docs/BACKLOG.md` e evidências em `spec.md`. Concluir revisão e verificações antes de avançar no ciclo.

## Último incremento

- B05.5c3 integrado na PR #104 (c0237e3), após CI transações #150/processador #156 e Secret Scan #57 verdes no SHA final. Retry/rollback/DLQ reais e ativação manual conferida; spec 9.50.

## Próximo

- [ ] B05.5d1 — Em execução: compilar/revisar orquestração e comprovar dois JARs reais/bancos próprios, POST → duas outboxes → GET APROVADA/REJEITADA no CI. Sem saída fabricada; B05 só termina após duplicata/replay em d2.
