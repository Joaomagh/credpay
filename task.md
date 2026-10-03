# CredPay — Tarefas

> Um incremento = um resultado pequeno e verificável. Coordenar pelos papéis em `docs/roles/`; manter prioridade/aceite em `docs/BACKLOG.md` e evidências em `spec.md`. Concluir revisão e verificações antes de avançar no ciclo.

## Último incremento

- B05.4b validado na PR #98: CI #126 verde com 170 testes e Secret Scan #33 verde. Disputas reais, conflitos e liberação após rollback comprovados; spec 9.44. Refatoração local verde; checks do SHA final obrigatórios antes de integrar.

## Próximo

- [ ] B05.4c — Em TDD, preservar representação original PENDENTE no replay do POST, mesmo após resultado aplicado, enquanto GET continua final. Teste HTTP/PostgreSQL exige dados originais/escala, histórico único e outbox intacta; nunca atualizar estado de volta para PENDENTE.
