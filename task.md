# CredPay — Tarefas

> Um incremento = um resultado pequeno e verificável. Coordenar pelos papéis em `docs/roles/`; manter prioridade/aceite em `docs/BACKLOG.md` e evidências em `spec.md`. Concluir revisão e verificações antes de avançar no ciclo.

## Último incremento

- B08.2b integrado na PR #110 (`f6e0dcf`): transações #172 (279 testes), Flow #21 e Scan #79 verdes em `0a0725b`. Concorrência/Atomicidade fecham antes do stop; PublicarOutbox ainda dez warnings, spec 9.56.

## Agora

- [ ] B08.2c — PR #111: red #174/green #175 (par11/suíte279), contexto compartilhado fechado antes do stop. Par temporário retirado; checks finais antes de integrar, spec 9.57.

## Próximo

- [ ] B08.2d — concorrência do processador: red/green na suíte padrão, cinco cenários preservados e guard antes do stop. FALHOU aguarda decisão do Navigator.
