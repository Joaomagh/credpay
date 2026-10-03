# CredPay — Tarefas

> Um incremento = um resultado pequeno e verificável. Coordenar pelos papéis em `docs/roles/`; manter prioridade/aceite em `docs/BACKLOG.md` e evidências em `spec.md`. Concluir revisão e verificações antes de avançar no ciclo.

## Último incremento

- B05.5d2 validado na PR #106: Flow CI #4/transações #155/Secret Scan #62 verdes, duplicatas/ack/replay e cinco tabelas intactas nos dois finais reais. Integrar após checks no SHA final para encerrar B05; spec 9.52.

## Próximo

- [ ] B06.1 — Após integrar d2, parar somente o processador, comprovar POST PENDENTE/outbox publicada/fila pronta sem decisão/histórico e recuperar após reinício real com causalidade original. FALHOU aguarda definição de produto; não bloqueia este experimento.
