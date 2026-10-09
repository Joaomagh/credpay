# CredPay — Tarefas

> Um incremento = um resultado pequeno e verificável. Papéis em `docs/roles/`, prioridade/aceite em `docs/BACKLOG.md` e evidências em `spec.md`. Revisão e checks antes de avançar.

## Último incremento

- B08.6b.3 integrado na PR #126 (b5e001f), sete gates verdes em 5f5e406. Seis IDs Rabbit/Netty ausentes nos quatro alvos, cobertura79/222; HIGH1/JAR2/imagem e CRITICAL2. Avisos Hikari do processador permanecem explícitos.

## Agora

- [ ] B08.6b.4 — JDBC42.7.12 nos dois POMs, baseline anterior à resolução. Local168/75 e JARs verificados; exigir sete gates, ID54291 ausente nos quatro relatórios e cobertura79/222 antes de integrar.

## Próximo

- [ ] B08.6b.5 — Consolidar triagem Spring/OpenSSL e proposta de política de bloqueio/publicação, sem supressão ou aceitação automática de risco.

## Depois

- Runtime: digest oficial corrigido ainda não identificado; não trocar só a tag. Tratar Spring/política de bloqueio/publicação, ensaiar demo e definir mecanismo FALHOU. Kubernetes depende de direção específica (PR #118 inativa); observabilidade/sandbox/v1 pendentes.
