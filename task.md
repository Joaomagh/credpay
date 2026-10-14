# CredPay — Tarefas

> Um incremento = um resultado verificável. Prioridades em docs/BACKLOG.md; evidências em spec.md.

## Último incremento

- B06.8d integrado na PR #141/3d78e9c: seis gates4349a8d verdes, processador222/8m14, fixture20 e revisão sem bloqueantes. Registro/resposta controlados, sem transporte/ativação. Spec9.87.

## Agora

- [ ] B06.8e — Pedido auditável em Transações: primeiro teste PostgreSQL exige pedido/intenção preservando PENDENTE, POST e histórico. Red PostgreSQL confirmado (relation pedidos_encerramento does not exist); implementação positiva mínima em validação, sem aceite de replay/recusas/constraints. Caso somente importado na fixture. Spec9.88.

## Próximo

- Derivar criação original, persistir pedido/outbox própria, testar replay/conflito/recusa/rollback e disputa com resultado financeiro. Retirar etapa temporária antes do aceite.

## Depois

- Aplicação causal da confirmação e transporte controlado; autoridade/evidência operacional e auditoria tardia. Painel/Kubernetes/sandbox/segurança/ensaio pendentes. Estimativa global65%.
