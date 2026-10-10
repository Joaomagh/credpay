# CredPay — Tarefas

> Um incremento = um resultado verificável. Papéis em docs/roles/, prioridades em docs/BACKLOG.md e evidências em spec.md.

## Último incremento

- B06.6 integrado na PR #134/d17eb0e: Compose opt-in, deltas confirmed2 em ambos, AP/REJ/replays e três volumes/GET/POST preservados após down/up. Dois gates finais968dc4a verdes. Java/POM intocados; suites286/124 baseline133. Spec9.81.

## Agora

- [ ] B06.7 — Retorno sem rota observável por HTTP nos JARs reais. Dois cenários na fixture E2E existente, perfil diagnostics explícito, returned/PENDENTE/outbox íntegra e recuperação do mesmo evento. TDD exposição/leitura antes dos ajustes da fixture; preservar quatro cenários/cleanup. Spec9.82.

## Próximo

- [ ] Consolidar balanço de observabilidade/falhas e selecionar próximo critério de conclusão pronto.

## Depois

- FALHOU, sandbox, demo local e v1 pendentes. Kubernetes exige direção específica (PR #118 inativa). Spring/OpenSSL e política de bloqueio permanecem separados.

B06.7: red exposição40544b4/run38018934543, esperado200/404 nos dois JARs;6/2falhas/0erros/skips, antigos4 verdes. Perfil somente na fixture corrigido após prova; red de leitura cbf9974/run38019394069 confirmado (6/2 falhas,0 erros/skips); leitor HTTP real preparado e aguarda green. Estrutura/Checkstyle0 verdes. Spec9.82.
