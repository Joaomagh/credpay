# CredPay — Tarefas

> Um incremento = um resultado verificável. Prioridades em docs/BACKLOG.md; evidências em spec.md.

## Último incremento

- B06.8c integrado na PR #140/f804fd4: seis gates verdes em27227c2, processador201/8m19, fixture15. Proteção normal, recibo/replay/conflitos/rollback e espera real pelo lock. Spec9.86.

## Agora

- [ ] B06.8d/PR #141 — Três ciclos red/green PostgreSQL aprovados até12 casos. Oito caracterizações de atomicidade/corrida acrescentadas; local100/27,124s verde, revisão aprovada, foco temporário retirado. CI222/gates finais necessários. Caso de uso só na fixture, sem produção/API/transporte. Spec9.87.

## Próximo

- Integrar o caso controlado somente após CI/revisão finais. Depois preparar pedido auditável/outbox própria em Transações, permanecendo PENDENTE até confirmação causal; sem API operacional.

## Depois

- Transporte e aplicação causal no serviço de transações; autoridade/evidência operacional e auditoria tardia. Painel/Kubernetes/sandbox/segurança/ensaio pendentes. Estimativa global65%.
