# CredPay — Tarefas

> Um incremento = um resultado pequeno e verificável. Coordenar pelos papéis em `docs/roles/`; manter prioridade/aceite em `docs/BACKLOG.md` e evidências em `spec.md`. Concluir revisão e verificações antes de avançar no ciclo.

## Último incremento

- B04.8 em validação: red observado e 31 testes focados verdes; prova RabbitMQ/PostgreSQL aguarda CI. Busca limitada em 305 commits locais alcançáveis não encontrou os formatos de segredo pesquisados; B08.1 permanece para revisão complementar.

## Próximo

- [ ] B04.8 — Provar que conflito de identidade no listener opt-in vai diretamente à DLQ e preserva o resultado original, sem requeue repetido.
