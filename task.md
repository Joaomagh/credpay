# CredPay — Tarefas

> Um incremento = um resultado verificável. Papéis em docs/roles/, prioridades em docs/BACKLOG.md e evidências em spec.md.

## Último incremento

- B06.5 integrado na PR #133/6ed1f36: diagnostics opcional, seis testes HTTP e runbook. Head3d5e558, sete gates verdes; Trans286/2m52, Proc124/8m20, zero falhas/erros/skips/Hikari/dynamic. Três IDs Spring/OpenSSL permanecem. Spec9.80.

## Agora

- [ ] B06.6 — Provar contadores reais na demo Compose com switch Diagnostics/override opt-in. Lista200, deltas confirmed>=2 nos dois apps, mesmos processos; preservar preparo/fluxo/persistência/cleanup. Red404 real antes do override, controles seguros, green/gates antes de integrar. Spec9.81.

## Próximo

- [ ] Refinar o próximo diagnóstico de falha demonstrável após concluir a demo instrumentada.

## Depois

- FALHOU, sandbox, demo local e v1 pendentes. Kubernetes exige direção específica (PR #118 inativa). Spring/OpenSSL e política de bloqueio permanecem separados.

B06.6: red endpoint dac404f/run38017998523, esperado200/observado404 após healthUP, cleanupverde. Override mínimo acrescentado; controles COUNT red/green/parse/config aprovados. Asserção de delta com leitor provisório0 aguarda red real antes da leitura HTTP. Spec9.81.
