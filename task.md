# CredPay — Tarefas

> Um incremento = um resultado pequeno e verificável. Coordenar pelos papéis em `docs/roles/`; manter prioridade/aceite em `docs/BACKLOG.md` e evidências em `spec.md`. Concluir revisão e verificações antes de avançar no ciclo.

## Último incremento

- B06.2 integrado na PR #108 (`f1755be`): Flow #11/transações #162/Scan #69 verdes no SHA final `a50f352`. Replay sem rota conserva DLQ; correção/replay recuperam causa original; spec 9.54.

## Agora

- [ ] B08.2a — PR #109 validada: red #164, green #165 com par10/suíte279, pool fechado antes do stop e zero warnings próprios; Flow #14/Scan #72 verdes. Integrar após checks finais, spec 9.55.

## Próximo

- [ ] B08.2b — Diagnosticar somente fixture Atomicidade, preservar rollback e provar fechamento antes do stop com mesmo par/ordem antes/depois. FALHOU aguarda decisão do Navigator.
