# CredPay — Tarefas

> Um incremento = um resultado pequeno e verificável. Coordenar pelos papéis em `docs/roles/`; manter prioridade/aceite em `docs/BACKLOG.md` e evidências em `spec.md`. Concluir revisão e verificações antes de avançar no ciclo.

## Último incremento

- B05.4c integrado na PR #99, commit 7a562d0: CI #131/Secret Scan #38 verdes no último SHA; POST original PENDENTE/GET final e histórico/outbox intactos. Spec 9.45.

## Próximo

- [ ] B05.5a em execução — Parser isolado de TransacaoProcessada.v1 com red/green local; revisão e CI completo pendentes. Exigir envelope/propriedades AMQP, dois finais, nanos, campos extras compatíveis e rejeição segura. Sem listener/banco/dependência nova.
