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
- PR227: credenciais Kubernetes próprias/reuso comprovados;6 checks e gate93,5% com cobertura PowerShell real, mergeac94cb7.
- PR228: URLs literais e redirects recusados; Core/Noble reais,5 checks e gate96,3% no delta, merge648e785.
- PR230: imagens/fluxo nativos em ARM64 e AMD64,5 checks verdes, merge2078d39; spec§9.162.

## Agora

- [ ] Preparar inventário ARM64 explícito, preservando o perfil AMD64; depois validar sessão/contas e configuração da demo pública.

## Próximo

- S11 e S12-S14 confirmados CLOSED/FIXED em main. Preservar recuperação causal e achados contextuais.
- Revisar ocorrências contextuais por ID, mantendo achados da pesquisa e referências históricas preservados.
- Preparar publicação gratuita com HTTPS, retenção e limites validados. Contas Cloudflare/Oracle ainda dependem de João; nenhum deploy.

## Depois

- Ensaio pessoal e decisão final da v1 permanecem adiados; não declarar entendimento ou aceite por João a partir do CI.
- Acompanhar GnuTLS/inventário. Painel e pesquisa de sandbox continuam adiados.
