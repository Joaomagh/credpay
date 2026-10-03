# CredPay — Tarefas

> Um incremento = um resultado pequeno e verificável. Coordenar pelos papéis em `docs/roles/`; manter prioridade/aceite em `docs/BACKLOG.md` e evidências em `spec.md`. Concluir revisão e verificações antes de avançar no ciclo.

## Último incremento

- B05.4c validado na PR #99: CI #130/Secret Scan #37 verdes; 174 testes, POST original PENDENTE e GET final com histórico/outbox intactos. Integração aguarda checks no SHA documental final; spec 9.45.

## Próximo

- [ ] B05.5a — Após integrar B05.4c, parser isolado de TransacaoProcessada.v1: envelope/propriedades AMQP, dois finais, precisão de nanos, campos extras compatíveis e rejeição segura. Sem listener/banco/dependência nova.
