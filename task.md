# CredPay — Tarefas

> Um incremento = um resultado verificável. Prioridades em docs/BACKLOG.md; evidências em spec.md.

## Último incremento

- B06.8a integrado na PR #138/786d11e: seis gates436ce3a verdes, Proc132/8m03 e regra8. Arbitragem pura preserva decisão/saída, sem FALHOU operacional. Spec9.84.

## Agora

- [ ] B06.8b — Persistência preparatória de recebimento/resposta e outbox próprios. Teste PostgreSQL roundtrip preparado, aguarda red de relações ausentes antesV3. Foco temporário remoto antesverify normal; retirar nofinal. Sem caso operacional/alteração do processamento normal. Spec9.85.

## Próximo

- [ ] Integridade/rollback e depois adapter/lock compartilhado com processamento normal; provar replay/corrida/entrada atrasada antes de transporte. Não confirmar sem proteger decisão normal.

## Depois

- Autoridade/evidência de início operacional e auditoria tardia antes de ativação. Painel/Kubernetes/sandbox/segurança/ensaio continuam pendentes. Estimativa global65%, sem conclusão por quantidade de PRs.
