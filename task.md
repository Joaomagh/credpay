# CredPay — Tarefas

> Um incremento = um resultado pequeno e verificável. Coordenar pelos papéis em `docs/roles/`; manter prioridade/aceite em `docs/BACKLOG.md` e evidências em `spec.md`. Concluir revisão e verificações antes de avançar no ciclo.

## Último incremento

- B04.10 validado: CI #140 verde com 113 testes; reentrega real após commit/antes do ACK preservou resultado/outbox completos. Nenhuma alteração de produção; Secret Scan #5 verde. Warnings Hikari de conexão fechada registrados para diagnóstico em B08.2.

## Próximo

- [ ] B04.11 — Comprovar recusa efetiva do dead-lettering com DLQ cheia, retenção na origem e recuperação após liberar capacidade, sem ativar consumo operacional.
