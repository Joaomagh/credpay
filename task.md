# CredPay — Tarefas

> Um incremento = um resultado verificável. Papéis em docs/roles/, prioridades em docs/BACKLOG.md e evidências em spec.md.

## Último incremento

- B06.4 integrado na PR #132/d77d7f6: contador de transações, quatro outcomes, sete casos unitários e deltas reais. Head ce2f393 com seis gates verdes; Trans209:283/2m54, zero falhas/erros/skips, Hikari0/dynamic0. Três IDs Spring/OpenSSL permanecem. Spec9.79.

## Agora

- [ ] B06.5 — Perfil opcional diagnostics nos dois serviços: health/metrics, default health apenas, testes HTTP reais sem DB/Rabbit. TDD antes dos perfis; COUNT/labels/filtro e endpoints administrativos fechados. Sem dependências/deploy novos. Spec9.80.

## Próximo

- [ ] Refinar demonstração operacional das métricas após validar o perfil local.

## Depois

- Mecanismo FALHOU, sandbox verificável, demo e v1 pendentes. Kubernetes exige direção específica (PR #118 inativa). Spring/OpenSSL e política de bloqueio permanecem separados.
