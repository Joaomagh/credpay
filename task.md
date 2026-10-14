# CredPay — Tarefas

> Um incremento = um resultado verificável. Prioridades em docs/BACKLOG.md; evidências em spec.md.

## Último incremento

- B06.8d integrado na PR #141/3d78e9c: seis gates4349a8d verdes, processador222/8m14, fixture20 e revisão sem bloqueantes. Registro/resposta controlados, sem transporte/ativação. Spec9.87.

## Agora

- [ ] B06.8e — Pedido auditável em Transações: primeiro teste PostgreSQL exige pedido/intenção preservando PENDENTE, POST e histórico. Positivo verde; red válido26/22falhas3erros confirmou ausência de recusas/replay/constraints. Proteções e etapa33cenários verdes no PostgreSQL (cdb9c65). Foco temporário removido; aceite final aguarda verify319 e seis gates/revisão do último SHA. Caso somente importado na fixture. Spec9.88.

## Próximo

- Concluir gates e integrar PR #142; depois preparar resposta/recibo causal e evolução do histórico único (B06.8f), ainda sem transporte/ativação.

## Depois

- Aplicação causal da confirmação e transporte controlado; autoridade/evidência operacional e auditoria tardia. Painel/Kubernetes/sandbox/segurança/ensaio pendentes. Estimativa global65%.
