# CredPay — Tarefas

> Um incremento = um resultado pequeno e verificável. Coordenar pelos papéis em `docs/roles/`; manter prioridade/aceite em `docs/BACKLOG.md` e evidências em `spec.md`. Concluir revisão e verificações antes de avançar no ciclo.

## Último incremento

- B04.9 validado: 75 testes sem infraestrutura verdes e 112 testes completos no CI #136. Retry limitado comprovou rollback, recuperação e DLQ ao esgotar; sem dependência nova. B08.1 permanece pendente, sem evidência de vazamento nas buscas limitadas realizadas.

## Próximo

- [ ] B04.10 — Provar reentrega real após fechamento da conexão entre commit e ack, com `redelivered=true` e sem duplicar/alterar resultado ou outbox.
