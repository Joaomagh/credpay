# CredPay — Tarefas

> Um incremento = um resultado verificável. Prioridades em docs/BACKLOG.md; evidências em spec.md.

## Último incremento

- B06.8e integrado na PR #142/996942c: seis gates ee3b94a verdes, 319 testes de Transações em 2m56, fixture33 e revisão sem bloqueantes. Pedido auditável sem congelar TX, transporte ou aplicação de FALHOU. Spec9.88.

## Agora

- [ ] B06.8f — Preparar contrato/recibo causal da resposta e V7 própria. Preservar envelope, nanos, saída negativa e vínculo pedido/TX/criação; sem aplicar estado/histórico. Contrato13 e etapa PostgreSQL17 verdes (f7a60cd); foco temporário removido. Aguardam-se suíte349 e seis gates finais. Spec9.89.

## Próximo

- Provar roundtrip/constraints/rollback PostgreSQL e integrar após revisão/gates. Depois evoluir domínio/histórico único e aplicação controlada, incluindo negativa antes/depois do financeiro.

## Depois

- Transporte controlado; autoridade/evidência operacional e auditoria tardia. Painel/Kubernetes/sandbox/segurança/ensaio pendentes. Estimativa global65%.
