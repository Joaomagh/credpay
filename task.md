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
- PR232: inventários nativos e controlador medido,5 checks/gate100% do delta, mergee668a8b. HIGHAPT bloqueia publicação; spec§9.163.
- PR233:6 testes HTTP de sessão e fluxo em imagens verdes em ARM64/AMD64,5 checks/gate OK, mergec29af39; spec§9.164.
- PR234: proxy preparado,65testes/8jornadas reais,7checks/gate99,6% do delta, merge02cbeea; sem deploy, spec§9.165.
- PR235: RabbitMQ4.3.7 integrado em17581d5 após16checks; versão real/fluxo/sessão em ARM64/AMD64, persistência/recuperação e8jornadas verdes; spec§9.166.
- PR237: cliente5.37.0/Netty4.2.18,16checks/gate93% do delta;3MEDIUM ausentes dos4alvos, merge9d2cd92; spec§9.167.
- PR238: inventário banco/broker e guard de caminhos,5checks/gate94,1% do delta; merge dbf718f. Achados preservados; UBI9 recusada, spec§9.168.
- PR239: APT removido com UBI10,16checks/gate95,2% do delta; merge bfb7715. JARs/imagens sem achados nos2perfis; banco continua bloqueado, spec§9.169.

## Agora

- [ ] Preparar perfil public-demo e provar origem/sessão porHTTPS real noCI (spec§9.170). Política9unitários e71edge verdes; filtro ainda não conectado, redHTTPS esperado antes de integrar. Sem deploy ou aceite dos achados do banco.

## Próximo

- S11 e S12-S14 confirmados CLOSED/FIXED em main. Preservar recuperação causal e achados contextuais.
- Revisar ocorrências contextuais por ID, mantendo achados da pesquisa e referências históricas preservados.
- Preparar publicação gratuita com HTTPS, retenção e limites validados. Contas Cloudflare/Oracle ainda dependem de João; nenhum deploy.
- PostgreSQLBookworm continuaCRITICAL4/HIGH75; candidatoAlpine CRITICAL1/HIGH25. Investigar cobertura/origem dos achados e corrigir antes de publicar, sem supressão ou aceite residual.
- Após inventário/triagem, validar proxy no runtime Workers e perfil HTTPS/sessões/retenção com dados fictícios, antes do aceite externo.

## Depois

- Ensaio pessoal e decisão final da v1 permanecem adiados; não declarar entendimento ou aceite por João a partir do CI.
- Acompanhar GnuTLS/inventário. Painel e pesquisa de sandbox continuam adiados.
