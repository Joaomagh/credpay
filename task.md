# CredPay — Tarefas

> Um incremento = um resultado verificável. Prioridades em docs/BACKLOG.md; evidências em spec.md.

## Último incremento

- B06.8c integrado na PR #140/f804fd4: seis gates verdes em27227c2, processador201/8m19, fixture15. Proteção normal, recibo/replay/conflitos/rollback e espera real pelo lock. Spec9.86.

## Agora

- [ ] B06.8d/PR #141 — Red PostgreSQL confirmado e primeira escrita positiva implementada; local100/28,262s verde/revisada. Green PostgreSQL positivo pendente. Negativa/replay/conflitos ainda não implementados; caso de uso só na fixture, sem produção/API/transporte. Spec9.87.

## Próximo

- Após red esperado, persistir sob lock pedido/TX/criação. Provar negativa preservando decisão financeira, replay antes de Clock/UUID, conflitos, atomicidade e disputa. Retirar foco temporário antes do aceite.

## Depois

- Transporte e aplicação causal no serviço de transações; autoridade/evidência operacional e auditoria tardia. Painel/Kubernetes/sandbox/segurança/ensaio pendentes. Estimativa global65%.
