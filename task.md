# CredPay — Tarefas

> Um incremento = um resultado verificável. Papéis em docs/roles/, prioridades em docs/BACKLOG.md e evidências em spec.md.

## Último incremento

- B08.2f integrado na PR #129/ccc61d2: sete gates finais verdes em032daec; processador114/8m14, duas capturas fechadas/PG ativo e zero warnings próprios. Autenticação Read comprovada. Pool5 ainda dez warnings; três IDs Spring/OpenSSL permanecem.

## Agora

- [ ] B08.2g — Provar fechamento da fixture ProcessamentoServiceApplicationTest. Preservar health HTTP/replay, dois casos e contexto compartilhado; capturar pools antes dos casos e observar stop real. Red exclusivo de teardown antes da correção; depois green e suíte114/gates. Refinamento revisado, implementação ainda não iniciada.

## Próximo

- [ ] Refinar somente a próxima fixture confirmada ou observabilidade do fluxo; decisão Spring/runtime/política continua separada, sem ampliação automática.

## Depois

- Spring/OpenSSL: três IDs aguardam correção/direção; proposta de bloqueio externo não é gate implementado. Runtime oficial corrigido ainda não identificado na consulta registrada. Demo, mecanismo FALHOU, observabilidade/sandbox e v1 pendentes. Kubernetes exige direção específica (PR #118 inativa).
