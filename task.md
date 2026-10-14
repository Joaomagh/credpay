# CredPay — Tarefas

> Um incremento = um resultado verificável. Prioridades em docs/BACKLOG.md; evidências em spec.md.

## Último incremento

- B06.8b integrado na PR #139/5ad7e3d: seis gates verdes em d80a899, processador 177/8m30, fixture PostgreSQL 45. Schema preparatório, sem fence operacional. Spec9.85.

## Agora

- [ ] B06.8c/PR #140 — Red real PostgreSQL comprovou aprovação indevida após seed positivo. Guard, retorno sealed e recibo V4 escritos depois da prova; local 99/28,429s verde. Green PostgreSQL pendente; replay/conflitos/rollback/corrida necessários antes de integrar. Writer de encerramento indisponível. Spec9.86.

## Próximo

- Completar replay, rollback e corrida sob lock compartilhado; integrar somente com suíte afetada, gates finais e revisão. Caso de encerramento registrado apenas em testes.

## Depois

- Transporte e aplicação causal no serviço de transações. Autoridade/evidência operacional e auditoria tardia antes de ativação. Painel/Kubernetes/sandbox/segurança/ensaio permanecem pendentes; estimativa global 65%.
