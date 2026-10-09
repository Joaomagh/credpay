# CredPay — Tarefas

> Um incremento = um resultado verificável. Papéis em docs/roles/, prioridades em docs/BACKLOG.md e evidências em spec.md.

## Último incremento

- B08.2f integrado na PR #129/ccc61d2: sete gates finais verdes em032daec; processador114/8m14, duas capturas fechadas/PG ativo e zero warnings próprios. Autenticação Read comprovada. Pool5 ainda dez warnings; três IDs Spring/OpenSSL permanecem.

## Agora

- [ ] B08.2g — Guard red preparado na fixture ProcessamentoServiceApplicationTest, dois casos health HTTP/replay preservados. Compilar/revisar e confirmar red real na CI antes de corrigir. Foco temporário; depois green/suíte114/gates e retirada do foco. Spec9.77.

## Próximo

- [ ] Refinar somente a próxima fixture confirmada ou observabilidade do fluxo; decisão Spring/runtime/política continua separada, sem ampliação automática.

## Depois

- Spring/OpenSSL: três IDs aguardam correção/direção; proposta de bloqueio externo não é gate implementado. Runtime oficial corrigido ainda não identificado na consulta registrada. Demo, mecanismo FALHOU, observabilidade/sandbox e v1 pendentes. Kubernetes exige direção específica (PR #118 inativa).
