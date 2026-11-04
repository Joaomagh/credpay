# Candidato ao fechamento da v1

Registro inicial de24/10/2026, revisto em25/10 após os aceites técnicos locais. **Candidato operacional condicionado; ainda não promovido a release final.** Nenhuma tag ou publicação foi criada por este registro.

## Identidade congelada

- Candidato operacional do repositório: `e07f0aa9f3ee48543d0b254e23e5d3007b7c78ab`, merge da PR201.
- Referência documental anterior: `463f9bc94fea86e1f1e09d87e23e69e47aecff2e`; seus checks permanecem vinculados àquele corte.
- Código validado na PR187: `3406d2d315901634d8484f4990d2509a80e1ec9a`, integrado em `6f77f8e16c46c6bd31ab13ec7a38ae1e6da690aa`.
- Código operacional validado na PR201: `5bbdf19ebc33c61ac85f11e25fbbe64a7295b552`, com a mesma árvore Git do candidato e07f0aa. O delta desde463f9bc inclui os launchers/controles operacionais; fontes, dependências, Dockerfiles e configurações versionadas dos três módulos permanecem iguais.
- Árvores Git do candidato: Transações `25633928f25a155d66c7933529de506dc601caf2`; Processamento `eb8964989763e58a25d11f06a261901a2ba02663`; frontend `f81f3c7fa81cd63472d81394425a49de1e254fb8`.

Essas árvores identificam fontes e configuração versionadas, não os bytes de JARs ou imagens reconstruídos. Não renomear uma imagem antiga para a tag deste candidato. Uma mudança relevante reabre seus checks e a identificação do candidato.

O corte operacional incorpora o perfil Kubernetes Local Windows e o diagnóstico causal por métricas. João concluiu Validate/Remove Kubernetes e Run/Remove do diagnóstico em25/10, preservando credpay-demo. Evidências em spec§9.136.6/9.138.1. As imagens f412652 continuam vinculadas às fontes dos aplicativos; não atribuir a essa tag antiga a identidade dos launchers posteriores. Mudança operacional relevante reabre sua validação; a decisão de release permanece condicionada aos critérios pessoais e de fechamento.

## Aceites conferidos

| Aceite | Evidência | Estado |
|---|---|---|
| Contrato HTTP e persistência de Transações | [CI](https://github.com/Joaomagh/credpay/actions/runs/40109186031): 525 testes, zero falhas/erros/skips | Comprovado no CI |
| Fluxo e recuperação controlados | [Flow](https://github.com/Joaomagh/credpay/actions/runs/40109185974): 12 casos | Comprovado no CI |
| Imagens e Compose | [Images](https://github.com/Joaomagh/credpay/actions/runs/40109185957), [Compose](https://github.com/Joaomagh/credpay/actions/runs/40109186077), incluindo persistência e seis cenários de navegador | Comprovado no CI |
| Kubernetes descartável autorizado | [Kubernetes](https://github.com/Joaomagh/credpay/actions/runs/40109185990) | Comprovado somente no CI |
| Console Angular | [Frontend](https://github.com/Joaomagh/credpay/actions/runs/40109186004), 35 testes locais e seis jornadas reais no Compose CI | Comprovado nos recortes descritos |
| Inventário e segredos do código testado | [Inventory](https://github.com/Joaomagh/credpay/actions/runs/40109185999), [Scan](https://github.com/Joaomagh/credpay/actions/runs/40109185953); artefato e limites em [V1_VALIDATION](V1_VALIDATION.md) | HIGH0/CRITICAL0 nos quatro alvos; MEDIUM/LOW preservados, sem aceite universal |
| Checks do corte documental anterior463f9bc | [Inventory](https://github.com/Joaomagh/credpay/actions/runs/40110119178), [Scan](https://github.com/Joaomagh/credpay/actions/runs/40110119100) | Verdes naquele corte; não são nova execução das oito suítes |
| Checks do código operacional incorporado em e07f0aa | [Compose](https://github.com/Joaomagh/credpay/actions/runs/40224018659), [Scan](https://github.com/Joaomagh/credpay/actions/runs/40224018545) no head5bbdf19, mesma árvore Git do merge | Controles Windows/Linux, diagnóstico real, persistência, browser e cleanup passaram; não é novo inventário |
| Demo Compose pessoal anterior | Retornos de João em22/10, imagens cf103ee; jornada e recuperação assistidas documentadas | Histórica; não prova execução local do código novo |
| Contadores locais de publicação | João construiu imagens f412652 e executou Prepare/Activate/Demo com Diagnostics: healthUP, metricsHTTP200, APROVADA/REJEITADA/replay e confirmed delta2 nos dois apps | Comprovado no ensaio assistido; diagnóstico de falha por métricas ainda separado |
| Diagnóstico causal por métricas | [Compose CI40222821313](https://github.com/Joaomagh/credpay/actions/runs/40222821313) e Run local de João em25/10: returned delta3 nos dois publicadores, PENDENTE/outbox original, recuperação causal e persistência; spec§9.138.1 | Run/Remove próprios comprovados localmente; credpay-demo preservado |
| Kubernetes local | João executou Validate do [perfil Windows](../infra/k8s/LOCAL.md): startup, persistência, fluxo/replay e recuperação causal passaram em25/10; detalhes em spec§9.136.6 | Validate e Remove próprios comprovados; percurso operacional assistido concluído |
| Explicação e autonomia pessoais | João adiou a prática; não presumir domínio a partir de testes | Pendente, adiado |

A sonda inicial sem resposta não identificava a causa. Depois de João abrir Docker Desktop, desktop-linux respondeu linux e o ensaio acima passou. Preflight Kubernetes informado por João: endpoint npipe:////./pipe/dockerDesktopLinuxEngine, driveroverlayfs,12CPUs, memória12431081472bytes, arquiteturax86_64. O preflight isolado não comprovava o cluster; a execução assistida de25/10 é a evidência adicional de Validate no Windows. O agente não acessou Docker, `.env` ou kubeconfig padrão.

## Sequência de fechamento

1. Docker Linux retomado por João no contexto desktop-linux. Imagens f412652 construídas dos JARs preparados, cujos hashes foram conferidos contra o manifesto local; ausência de delta operacional desde essa revisão foi verificada. Não renomear imagens cf103ee antigas para a nova tag.
2. Prepare/Activate/Demo com Diagnostics passaram em24/10: health/metrics acessíveis, dois consumidores ACK/prefetch10/1, dois resultados/replays e delta confirmed2 nos dois processos entre baseline/leitura da demo. Fonte: resumos dos comandos executados por João. COUNT mede tentativas e não prova sozinho consumo, commit ou transações únicas; os resultados vieram das verificações separadas do roteiro. Configuração e volumes existentes preservados, sem Down após essa medição.
3. Diagnóstico causal local sem rota concluído por João em25/10: returned delta3 nos dois publicadores, pedido/evento original preservados, recuperação sem duplicação e down/up persistente. Remove descartou somente recursos do ensaio, preservando credpay-demo. Não cobre nack/error; contagem de tentativas não equivale a transações únicas.
4. Validate local passou em25/10 após correções do perfil autorizado: startup, PVCs, fluxo/replay e parada/recuperação. Remove também passou e encerrou somente o cluster próprio, preservando Compose e volumes. A exceção local não concede acesso Docker ao agente nem permite executar os scripts CI por mera troca de nome.
5. Quando João retomar a prática, demonstrar entendimento/autonomia. Só então conferir todos os critérios e registrar a decisão final. Este documento não reduz o critério de aprendizado.

Grafana/Prometheus e pesquisa de sandbox continuam adiados. Achados de GnuTLS e correções disponíveis de curl/zlib permanecem na [triagem](../infra/security/TRIAGE.md), sem supressão, promoção automática de runtime ou autorização de implantação externa.
