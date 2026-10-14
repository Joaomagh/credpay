# CredPay — Tarefas

> Um incremento = um resultado verificável. Prioridades em docs/BACKLOG.md; evidências em spec.md.

## Último incremento

- B06.8b integrado na PR #139/5ad7e3d: seis gates verdes em d80a899, processador 177/8m30, fixture PostgreSQL 45. Schema preparatório, sem fence operacional. Spec9.85.

## Agora

- [ ] B06.8c/PR #140 — Proteção normal/recibo V4 após três ciclos red/green PostgreSQL; foco12 aprovado. Caracterizações finais de rollback e espera pelo lock acrescentadas; etapa temporária retirada. Local99 verde, CI201/gates/revisão final necessários. Writer de encerramento indisponível. Spec9.86.

## Próximo

- B06.8d: depois da proteção normal integrada, registrar pedido/resposta/outbox sob lock compartilhado; caso de uso somente nos testes. Provar vencedor, replay, rollback e corrida antes de transportar.

## Depois

- Transporte e aplicação causal no serviço de transações. Autoridade/evidência operacional e auditoria tardia antes de ativação. Painel/Kubernetes/sandbox/segurança/ensaio permanecem pendentes; estimativa global 65%.
