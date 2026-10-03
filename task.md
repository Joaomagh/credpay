# CredPay — Tarefas

> Um incremento = um resultado pequeno e verificável. Coordenar pelos papéis em `docs/roles/`; manter prioridade/aceite em `docs/BACKLOG.md` e evidências em `spec.md`. Concluir revisão e verificações antes de avançar no ciclo.

## Último incremento

- B05.5c1 validado na PR #102: CI #142/Secret Scan #49 verdes, commit/ack nos dois finais e replay comprovados. Integração depende dos CIs aplicáveis no SHA final. Ativação operacional proibida; spec 9.48.

## Próximo

- [ ] B05.5c2 — Após integrar c1, rejeitar contrato inválido/conflito/recusa permanente para DLQ sem requeue e sem alterar banco, com diagnóstico seguro. TDD antes da classificação; retry operacional fica em c3.
