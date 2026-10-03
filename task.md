# CredPay — Tarefas

> Um incremento = um resultado pequeno e verificável. Coordenar pelos papéis em `docs/roles/`; manter prioridade/aceite em `docs/BACKLOG.md` e evidências em `spec.md`. Concluir revisão e verificações antes de avançar no ciclo.

## Último incremento

- B05.5b validado no CI transações #136 (257 testes)/Secret Scan #43, PR #101; integração ainda depende do CI processador aplicável e checks finais. Spec 9.47.

## Próximo

- [ ] B05.5c — Após integrar topologia/políticas, implementar listener seguro do retorno: commit antes de ack, replay, rejeições permanentes e retry operacional limitado. Refinar menor slice sem anunciar consumo seguro antes de comprovar classificação/limites.
