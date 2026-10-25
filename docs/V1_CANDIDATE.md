# Candidato ao fechamento da v1

Registro de 24/10/2026. **Identificado para validação; ainda não promovido a release final.** Nenhuma tag ou publicação foi criada por este registro.

## Identidade congelada

- Candidato do repositório: `463f9bc94fea86e1f1e09d87e23e69e47aecff2e`.
- Código validado na PR187: `3406d2d315901634d8484f4990d2509a80e1ec9a`, integrado em `6f77f8e16c46c6bd31ab13ec7a38ae1e6da690aa`.
- Entre o head testado e o candidato mudaram somente BACKLOG, V1_VALIDATION, TRIAGE, spec e task; fontes, dependências, imagens e configurações operacionais dos aplicativos não mudaram.
- Árvores Git do candidato: Transações `25633928f25a155d66c7933529de506dc601caf2`; Processamento `eb8964989763e58a25d11f06a261901a2ba02663`; frontend `f81f3c7fa81cd63472d81394425a49de1e254fb8`.

Essas árvores identificam fontes e configuração versionadas, não os bytes de JARs ou imagens reconstruídos. Não renomear uma imagem antiga para a tag deste candidato. Uma mudança relevante reabre seus checks e a identificação do candidato.

O perfil Kubernetes Local Windows é incremento posterior, iniciado em0286262603fcba2994540d797a45559e31aac07d e corrigido até PR198/db363eb após seus controles e CI. João concluiu Validate em25/10, incluindo persistência e recuperação; Remove também passou, preservando Compose e volumes. Evidência em spec§9.136.6. O candidato operacional do repositório deverá ser reidentificado no fechamento. As imagens f412652 continuam vinculadas às fontes dos aplicativos, que não mudaram por esse perfil. Não atribuir a esse SHA antigo a validação do launcher novo.

## Aceites conferidos

| Aceite | Evidência | Estado |
|---|---|---|
| Contrato HTTP e persistência de Transações | [CI](https://github.com/Joaomagh/credpay/actions/runs/40109186031): 525 testes, zero falhas/erros/skips | Comprovado no CI |
| Fluxo e recuperação controlados | [Flow](https://github.com/Joaomagh/credpay/actions/runs/40109185974): 12 casos | Comprovado no CI |
| Imagens e Compose | [Images](https://github.com/Joaomagh/credpay/actions/runs/40109185957), [Compose](https://github.com/Joaomagh/credpay/actions/runs/40109186077), incluindo persistência e seis cenários de navegador | Comprovado no CI |
| Kubernetes descartável autorizado | [Kubernetes](https://github.com/Joaomagh/credpay/actions/runs/40109185990) | Comprovado somente no CI |
| Console Angular | [Frontend](https://github.com/Joaomagh/credpay/actions/runs/40109186004), 35 testes locais e seis jornadas reais no Compose CI | Comprovado nos recortes descritos |
| Inventário e segredos do código testado | [Inventory](https://github.com/Joaomagh/credpay/actions/runs/40109185999), [Scan](https://github.com/Joaomagh/credpay/actions/runs/40109185953); artefato e limites em [V1_VALIDATION](V1_VALIDATION.md) | HIGH0/CRITICAL0 nos quatro alvos; MEDIUM/LOW preservados, sem aceite universal |
| Checks aplicáveis ao SHA do candidato | [Inventory](https://github.com/Joaomagh/credpay/actions/runs/40110119178), [Scan](https://github.com/Joaomagh/credpay/actions/runs/40110119100) | Verdes; não são nova execução das oito suítes |
| Demo Compose pessoal anterior | Retornos de João em22/10, imagens cf103ee; jornada e recuperação assistidas documentadas | Histórica; não prova execução local do código novo |
| Contadores locais de publicação | João construiu imagens f412652 e executou Prepare/Activate/Demo com Diagnostics: healthUP, metricsHTTP200, APROVADA/REJEITADA/replay e confirmed delta2 nos dois apps | Comprovado no ensaio assistido; diagnóstico de falha por métricas ainda separado |
| Diagnóstico causal por métricas | [Compose CI40222821313](https://github.com/Joaomagh/credpay/actions/runs/40222821313): returned delta1 nos dois publicadores, PENDENTE/outbox original e recuperação causal; [roteiro descartável](../infra/compose/DIAGNOSTICS.md) | Implementado e comprovado no CI; Run/Remove por João ainda pendentes |
| Kubernetes local | João executou Validate do [perfil Windows](../infra/k8s/LOCAL.md): startup, persistência, fluxo/replay e recuperação causal passaram em25/10; detalhes em spec§9.136.6 | Validate e Remove próprios comprovados; percurso operacional assistido concluído |
| Explicação e autonomia pessoais | João adiou a prática; não presumir domínio a partir de testes | Pendente, adiado |

A sonda inicial sem resposta não identificava a causa. Depois de João abrir Docker Desktop, desktop-linux respondeu linux e o ensaio acima passou. Preflight Kubernetes informado por João: endpoint npipe:////./pipe/dockerDesktopLinuxEngine, driveroverlayfs,12CPUs, memória12431081472bytes, arquiteturax86_64. O preflight isolado não comprovava o cluster; a execução assistida de25/10 é a evidência adicional de Validate no Windows. O agente não acessou Docker, `.env` ou kubeconfig padrão.

## Sequência de fechamento

1. Docker Linux retomado por João no contexto desktop-linux. Imagens f412652 construídas dos JARs preparados, cujos hashes foram conferidos contra o manifesto local; ausência de delta operacional desde essa revisão foi verificada. Não renomear imagens cf103ee antigas para a nova tag.
2. Prepare/Activate/Demo com Diagnostics passaram em24/10: health/metrics acessíveis, dois consumidores ACK/prefetch10/1, dois resultados/replays e delta confirmed2 nos dois processos entre baseline/leitura da demo. Fonte: resumos dos comandos executados por João. COUNT mede tentativas e não prova sozinho consumo, commit ou transações únicas; os resultados vieram das verificações separadas do roteiro. Configuração e volumes existentes preservados, sem Down após essa medição.
3. Para diagnóstico causal sem rota, avaliar roteiro próprio em recursos descartáveis; a demo acima comprova contadores de publicação, não reproduz sozinha returned/nacked/error. Não remover bindings do broker persistente de João.
4. Validate local passou em25/10 após correções do perfil autorizado: startup, PVCs, fluxo/replay e parada/recuperação. Remove também passou e encerrou somente o cluster próprio, preservando Compose e volumes. A exceção local não concede acesso Docker ao agente nem permite executar os scripts CI por mera troca de nome.
5. Quando João retomar a prática, demonstrar entendimento/autonomia. Só então conferir todos os critérios e registrar a decisão final. Este documento não reduz o critério de aprendizado.

Grafana/Prometheus e pesquisa de sandbox continuam adiados. Achados de GnuTLS e correções disponíveis de curl/zlib permanecem na [triagem](../infra/security/TRIAGE.md), sem supressão, promoção automática de runtime ou autorização de implantação externa.
