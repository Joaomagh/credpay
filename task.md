# CredPay - Tarefas

> Um incremento = um resultado verificável. Prioridades em docs/BACKLOG.md; evidências em spec.md.

## Últimos incrementos

- Candidato técnico da v1 e ensaios assistidos em docs/V1_VALIDATION.md e docs/V1_CANDIDATE.md; aceite pessoal adiado.
- Contas, autorização, frontend e limites de recursos em spec§9.142-9.149; cobertura Java/Angular em§9.150.
- PR219: preparação Sonar validada com542 testes transações,340 processamento e45 Angular. Primeiro envio CI importou Java/cobertura e revelou dois achados Java; gate reprovou a medição Python ausente.
- PR220: seis downloads restringidos a HTTPS. Nove checks verdes após repetir somente o inventário que falhou por HTTP504; aguardam reanálise para encerrar S05-S10.

## Agora

- [ ] Validar no CI e na reanálise a proteção401/sem cota para identidade ausente em LimitesDemo. Red real em2 casos,3 testes locais e Checkstyle verdes. Cinco falsos positivos autorizados registrados em spec§9.155.

## Próximo

- Conferir o primeiro push main com XML Python após PR221/gate OK; manter visíveis os avisos do analisador.
- Remover custo superlinear das duas regex do frontend; conferir semântica da moeda e prova de cancelamento HTTP com a skill de QA.
- Revisar ocorrências contextuais por ID, mantendo achados da pesquisa e referências históricas preservados.
- Preparar publicação gratuita com HTTPS, retenção e limites validados. Contas Cloudflare/Oracle ainda dependem de João; nenhum deploy.

## Depois

- Ensaio pessoal e decisão final da v1 permanecem adiados; não declarar entendimento ou aceite por João a partir do CI.
- Acompanhar GnuTLS/inventário. Painel e pesquisa de sandbox continuam adiados.
