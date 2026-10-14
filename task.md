# CredPay — Tarefas

> Um incremento = um resultado verificável. Prioridades em docs/BACKLOG.md; evidências em spec.md.

## Último incremento

- Contrato de eventos/arbitragem integrado na PR #137/bbeab54 após Scan/revisão/UTF-8/diff. Direção: confirmação coordenada; João aprovou primeira prova controlada somente em teste, sem API. Spec9.83/9.84.

## Agora

- [ ] B06.8a — Validar regra pura ArbitrarEncerramento: preservar decisão/saída, recusar identidades divergentes, ausência exige bloqueio. TDD8 e verify local90 verdes; CI132/gates e revisão ainda necessários. Sem bloqueio persistente ou FALHOU implementados. Spec9.84.

## Próximo

- [ ] Bloqueio durável e recebimento/resposta/outbox atômicos sob lock compartilhado com processamento normal. TDD PostgreSQL: rollback, replay/conflito e disputa pedido/decisão; depois transporte.

## Depois

- Autoridade/evidência de início operacional e auditoria tardia antes de ativação. Painel/Kubernetes/sandbox/segurança/ensaio continuam pendentes. Estimativa global65%, sem conclusão por quantidade de PRs.
