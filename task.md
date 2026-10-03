# CredPay — Tarefas

> Um incremento = um resultado pequeno e verificável. Coordenar pelos papéis em `docs/roles/`; manter prioridade/aceite em `docs/BACKLOG.md` e evidências em `spec.md`. Concluir revisão e verificações antes de avançar no ciclo.

## Último incremento

- B05 concluído e integrado na PR #106 (`2b85d99`): Flow #5/transações #156/Secret Scan #63 verdes no SHA final; duplicatas/replay preservam cinco tabelas e resposta original.

## Agora

- [ ] B06.1 — PR #107 validada: Flow #7 (3 cenários), transações #158 (279 testes) e Scan #65 verdes. Integrar após checks no SHA final; spec 9.53.

## Próximo

- [ ] B06.2 — Após integrar B06.1, provar replay de DLQ depois de corrigir limite ausente, com confirmação antes de remover a mensagem original. FALHOU aguarda decisão do Navigator.
