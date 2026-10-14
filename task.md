# CredPay — Tarefas

> Um incremento = um resultado verificável. Prioridades em docs/BACKLOG.md; evidências em spec.md.

## Último incremento

- B06.8b integrado na PR #139/5ad7e3d: seis gates verdes em d80a899, processador 177/8m30, fixture PostgreSQL 45. Schema preparatório, sem fence operacional. Spec9.85.

## Agora

- [ ] B06.8c — Arbitragem durável exclusivamente controlada. Pedido validado por TDD9; primeiro red de criação bloqueada no PostgreSQL ainda necessário. Proteger chamada direta normal e recibo antes de oferecer confirmação positiva. Sem API/listener/transporte de encerramento. Spec9.86.

## Próximo

- Completar replay, rollback e corrida sob lock compartilhado; integrar somente com suíte afetada, gates finais e revisão. Caso de encerramento registrado apenas em testes.

## Depois

- Transporte e aplicação causal no serviço de transações. Autoridade/evidência operacional e auditoria tardia antes de ativação. Painel/Kubernetes/sandbox/segurança/ensaio permanecem pendentes; estimativa global 65%.
