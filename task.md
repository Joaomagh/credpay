# CredPay — Tarefas

> Um incremento = um resultado verificável. Prioridades em docs/BACKLOG.md; evidências em spec.md.

## Último incremento

- B06.8a integrado na PR #138/786d11e: seis gates436ce3a verdes, Proc132/8m03 e regra8. Arbitragem pura preserva decisão/saída, sem FALHOU operacional. Spec9.84.

## Agora

- [ ] B06.8b/PR #139 — Validar schema preparatório e45 casos PostgreSQL. TDD por ciclo, foco44 aprovado e rollback caracterizado; etapa temporária retirada. Local90/26,746s/Checkstyle0 e revisão aprovados; CI177 e gates finais necessários. Sem caso operacional/fence efetivo/API. Spec9.85.

## Próximo

- [ ] Adapter e arbitragem durável sob lock compartilhado, incluindo proteção do processamento normal. Provar replay/rollback/corrida e entrada atrasada antes de transportar confirmação positiva.

## Depois

- Autoridade/evidência operacional e auditoria tardia antes de ativação. Painel/Kubernetes/sandbox/segurança/ensaio continuam pendentes. Estimativa global65%, sem conclusão por quantidade de PRs.
