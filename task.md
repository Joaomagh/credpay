# CredPay - Tarefas

> Um incremento = um resultado verificável. Prioridades em docs/BACKLOG.md; evidências em spec.md.

## Últimos incrementos

- Candidato técnico da v1 e ensaios assistidos em docs/V1_VALIDATION.md e docs/V1_CANDIDATE.md; aceite pessoal adiado.
- Contas, autorização, frontend e limites de recursos em spec§9.142-9.149; cobertura Java/Angular em§9.150.
- PR219/221: ingestão Java/Angular/Python e análise contínua com gate OK, sem reduzir condições. Avisos preservados em SONARQUBE_CI.md.
- PR220/222: seis downloads e NPE CLOSED/FIXED em main; cinco falsos positivos autorizados com justificativas individuais.
- PR223: decimais lineares,2 workers/46 Angular e8 jornadas reais verdes; ambos IDs CLOSED/FIXED em main.
- PR224: resultado antigo removido em consulta inválida,48 Angular/gate OK e Q04 CLOSED/FIXED.
- PR225: moeda/UUID com semântica correta e foco visível no mobile;50 Angular,8 jornadas reais e gate OK.
- PR226: variável automática preservada;6 checks, gate OK e recuperação causal/navegador aprovados, mergec0a3760.

## Agora

- [ ] Validar Secret Kubernetes próprio no CI/reanálise: criação única/reuso sem rotação,50 controles offline e guards verdes; spec§9.160.

## Próximo

- Conferir encerramento Q03/Q07 em main e preservar prova de recuperação causal.
- Endurecer redirects do download da pesquisa sem liberar o core; preservar validação de tamanho/hash/origem.
- Revisar ocorrências contextuais por ID, mantendo achados da pesquisa e referências históricas preservados.
- Preparar publicação gratuita com HTTPS, retenção e limites validados. Contas Cloudflare/Oracle ainda dependem de João; nenhum deploy.

## Depois

- Ensaio pessoal e decisão final da v1 permanecem adiados; não declarar entendimento ou aceite por João a partir do CI.
- Acompanhar GnuTLS/inventário. Painel e pesquisa de sandbox continuam adiados.
