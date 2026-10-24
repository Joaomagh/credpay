# Candidato ao fechamento da v1

Registro de 24/10/2026. **Identificado para validação; ainda não promovido a release final.** Nenhuma tag ou publicação foi criada por este registro.

## Identidade congelada

- Candidato do repositório: `463f9bc94fea86e1f1e09d87e23e69e47aecff2e`.
- Código validado na PR187: `3406d2d315901634d8484f4990d2509a80e1ec9a`, integrado em `6f77f8e16c46c6bd31ab13ec7a38ae1e6da690aa`.
- Entre o head testado e o candidato mudaram somente BACKLOG, V1_VALIDATION, TRIAGE, spec e task; fontes, dependências, imagens e configurações operacionais dos aplicativos não mudaram.
- Árvores Git do candidato: Transações `25633928f25a155d66c7933529de506dc601caf2`; Processamento `eb8964989763e58a25d11f06a261901a2ba02663`; frontend `f81f3c7fa81cd63472d81394425a49de1e254fb8`.

Essas árvores identificam fontes e configuração versionadas, não os bytes de JARs ou imagens reconstruídos. Não renomear uma imagem antiga para a tag deste candidato. Uma mudança relevante reabre seus checks e a identificação do candidato.

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
| Diagnóstico local por métricas | Sonda de24/10 não obteve resposta HTTP nas portas8080/8081; retomada depende do preflight de João | Pendente |
| Kubernetes local | João informou não saber/ainda não ter configurado; [proposta específica](KUBERNETES_LOCAL_PROPOSAL.md) | Pendente de direção e execução, não autorizado pela exceção CI |
| Explicação e autonomia pessoais | João adiou a prática; não presumir domínio a partir de testes | Pendente, adiado |

A sonda sem resposta não identifica a causa: aplicativo parado, porta diferente ou problema de conexão continuam hipóteses. Não acessou Docker, `.env` ou kubeconfig padrão.

## Sequência de fechamento

1. Retomar o Docker Linux com João; conferir o checkout e usar JARs/imagens correspondentes à revisão registrada. Preservar configuração e volumes existentes.
2. Executar `Prepare -Diagnostics`, `Activate -Diagnostics` e `Demo -Diagnostics`, uma etapa por vez. Exigir health/metrics acessíveis e delta confirmed de pelo menos2 nos dois processos, sem reiniciá-los entre baseline e leitura. Registrar os resumos reais e o SHA/tag usados.
3. Para diagnóstico causal sem rota, avaliar roteiro próprio em recursos descartáveis; a demo acima comprova contadores de publicação, não reproduz sozinha returned/nacked/error. Não remover bindings do broker persistente de João.
4. Definir um ambiente Kubernetes local autorizado e validar startup, PVCs, fluxo/replay e parada/recuperação com o mesmo recorte e limites. O plano local não autoriza executar o node privilegiado.
5. Quando João retomar a prática, demonstrar entendimento/autonomia. Só então conferir todos os critérios e registrar a decisão final. Este documento não reduz o critério de aprendizado.

Grafana/Prometheus e pesquisa de sandbox continuam adiados. Achados de GnuTLS e correções disponíveis de curl/zlib permanecem na [triagem](../infra/security/TRIAGE.md), sem supressão, promoção automática de runtime ou autorização de implantação externa.
