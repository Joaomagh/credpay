# CredPay - Tarefas

> Um incremento = um resultado verificável. Prioridades em docs/BACKLOG.md; evidências em spec.md.

## Últimos incrementos

- Candidato técnico da v1 e ensaios assistidos em docs/V1_VALIDATION.md e docs/V1_CANDIDATE.md; aceite pessoal adiado.
- Contas, autorização, frontend e limites de recursos em spec§9.142-9.149; cobertura Java/Angular em§9.150.
- PR219: preparação Sonar validada com542 testes transações,340 processamento e45 Angular. Primeiro envio CI importou Java/cobertura e revelou dois achados Java; gate reprovou a medição Python ausente.
- PR220: seis downloads restringidos a HTTPS. Nove checks verdes após repetir somente o inventário que falhou por HTTP504; aguardam reanálise para encerrar S05-S10.

## Agora

- [ ] Validar CI/reanálise e revisão manual da manipulação decimal linear do frontend.2 reds/greens em worker real,46 Angular/tipos/builds verdes locais; spec§9.156.

## Próximo

- Confirmar fechamento do NPE em main apósPR222/13 checks verdes e544 testes; gate main já OK após cobertura Python.
- Conferir semântica da moeda e prova de cancelamento HTTP com a skill de QA.
- Revisar ocorrências contextuais por ID, mantendo achados da pesquisa e referências históricas preservados.
- Preparar publicação gratuita com HTTPS, retenção e limites validados. Contas Cloudflare/Oracle ainda dependem de João; nenhum deploy.

## Depois

- Ensaio pessoal e decisão final da v1 permanecem adiados; não declarar entendimento ou aceite por João a partir do CI.
- Acompanhar GnuTLS/inventário. Painel e pesquisa de sandbox continuam adiados.
