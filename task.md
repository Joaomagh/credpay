# CredPay — Tarefas

> Um incremento = um resultado pequeno e verificável. Coordenar pelos papéis em `docs/roles/`; manter prioridade/aceite em `docs/BACKLOG.md` e evidências em `spec.md`. Concluir revisão e verificações antes de avançar no ciclo.

## Último incremento

- B04.11 integrado na PR #92 (`d02f29c`): recusa do worker, retenção e recuperação após liberar a DLQ comprovadas no CI #143, com 113 testes do `processamento-service`. CI #144 e Secret Scan #9 verdes no SHA final. Resumos, ADRs e pendências documentais reconciliados; warnings das fixtures seguem em B08.2, sem correção alegada.

## Agora

- [ ] B04.12 — Artefato de políticas, runbook e teste de importação/reaplicação preparados. Compilação e 75 testes sem infraestrutura verdes; Docker local indisponível. Aguardar broker real, suíte completa e revisão no CI antes de integrar. Depois avançar a B05.
