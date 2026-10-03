# CredPay — Tarefas

> Um incremento = um resultado pequeno e verificável. Coordenar pelos papéis em `docs/roles/`; manter prioridade/aceite em `docs/BACKLOG.md` e evidências em `spec.md`. Concluir revisão e verificações antes de avançar no ciclo.

## Último incremento

- B04.8 validado: red observado, 71 testes sem infraestrutura verdes localmente e 106 testes completos no CI #133. Conflito vai à DLQ sem alterar resultado/outbox. Busca limitada de histórico e log do CI sem os padrões de segredo pesquisados; B08.1 ainda pendente.

## Próximo

- [ ] B04.9 — Provar retry operacional limitado a três tentativas (esperas de 1 e 2 segundos), com recuperação ou DLQ ao esgotar e sem retry para erros permanentes.
