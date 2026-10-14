# CredPay — Tarefas

> Um incremento = um resultado verificável. Prioridades em docs/BACKLOG.md; evidências em spec.md.

## Último incremento

- B06.8f integrado na PR #143/6cd8608: seis gates90e0fb4 verdes, 349 testes em3m00, recibo17 e contrato13. Recibo preserva causalidade; não aplica estado/histórico. Spec9.89.

## Agora

- [ ] B06.8g — Aplicação controlada da confirmação: FALHOU e histórico único atômicos com recibo positivo; negativa preserva vencedor financeiro em ambos os ordenamentos. Domínio red41/8 e green195; aplicação/upgrade red2 confirmado. Positivo mínimo/V8 em validação, rodada22+upgrade exige guardas/replay/negativa ainda ausentes. Caso somente importado em teste. Spec9.90.

## Próximo

- TDD PostgreSQL para positivo causal, replay/recusa, negativo simétrico, rollback e corrida; provar migração/backfill e preservar GET/POST/contrato financeiro. Sem transporte/ativação.

## Depois

- Transporte controlado; autoridade/evidência operacional e auditoria tardia. Painel/Kubernetes/sandbox/segurança/ensaio pendentes. Estimativa global65%.
