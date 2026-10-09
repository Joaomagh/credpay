# CredPay — Tarefas

> Um incremento = um resultado pequeno e verificável. Papéis em `docs/roles/`, prioridade/aceite em `docs/BACKLOG.md` e evidências em `spec.md`. Revisão e checks antes de avançar.

## Último incremento

- B08.6b.2 integrado na PR #125 (35c4087), sete gates verdes em 07df2f0. Cinco IDs Jackson ausentes nos quatro alvos, cobertura71/214; HIGH5/JAR6/imagem e CRITICAL2. Avisos Hikari do processador permanecem explícitos.

## Agora

- [ ] B08.6b.3 — Cliente RabbitMQ5.34.0, Spring AMQP3.2.12 preservado nos dois JARs; local168/75 passou. Exigir suítes reais, Flow/Images/Compose/Scan, inventário sem quatro IDs e revisão antes de integrar.

## Próximo

- [ ] B08.6b.4 — Refinar baseline JDBC42.7.12 e corrigir HIGH54291; preservar migrations, transações, locks e snapshots nos testes reais.

## Depois

- Runtime: digest oficial corrigido ainda não identificado; não trocar só a tag. Tratar Spring/política de bloqueio/publicação, ensaiar demo e definir mecanismo FALHOU. Kubernetes depende de direção específica (PR #118 inativa); observabilidade/sandbox/v1 pendentes.
