# CredPay — Tarefas

> Um incremento = um resultado verificável. Papéis em docs/roles/, prioridades em docs/BACKLOG.md e evidências em spec.md.

## Último incremento

- B08.2g integrado na PR #130/4f3ed7f: seis gates finais verdes em8d29b3f; processador114/8m08, shutdown servidor/pool antes do PostgreSQL e zero warnings de conexão. Três IDs Spring/OpenSSL permanecem.

## Agora

- [ ] B06.3 — Contador de publicação: reds confirmed/returned/nacked/error comprovados, incluindo returned no RabbitMQ real. Sete testes novos e verify local82/26,338s verdes, Checkstyle0. Aguardar green da fixture real e suíte121/gates; depois remover foco temporário e validar head final. Sem dependência ou exposição HTTP nova. Spec9.78.

## Próximo

- [ ] Refinar somente a próxima fixture confirmada ou observabilidade do fluxo; decisão Spring/runtime/política continua separada, sem ampliação automática.

## Depois

- Spring/OpenSSL: três IDs aguardam correção/direção; proposta de bloqueio externo não é gate implementado. Runtime oficial corrigido ainda não identificado na consulta registrada. Demo, mecanismo FALHOU, observabilidade/sandbox e v1 pendentes. Kubernetes exige direção específica (PR #118 inativa).
