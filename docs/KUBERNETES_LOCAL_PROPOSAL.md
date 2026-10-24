# Proposta de validação Kubernetes local

Preparada em24/10/2026 a pedido de João. **Exceção local delimitada autorizada por João em24/10; perfil Windows implementado em [LOCAL.md](../infra/k8s/LOCAL.md), execução real ainda pendente.** A autorização e os controles offline não comprovam criação de cluster, instalação global ou demonstração local.

## Decisão necessária

[AGENTS.md §5](../AGENTS.md) determina: “Não usar modo privilegiado”. A exceção de15/10 abrange somente o runner hospedado e descartável do CI. Após revisar esta proposta na revisão f412652, João respondeu “Autorizar somente essa exceção local delimitada”. A nova exceção específica está registrada em AGENTS§5; não foi inferida do pedido genérico de validar localmente e não precisa ser solicitada novamente.

A rota proposta usa um único cluster kind descartável no Docker Desktop Linux já instalado. Os comandos que acessam o daemon serão executados por João no PowerShell; o agente prepara e revisa arquivos/comandos e interpreta os resumos. Não concede acesso Docker/socket ao agente.

**Benefício:** aproxima o ensaio local da baseline já comprovada no CI, sem instalar um Kubernetes global, ativar o Kubernetes interno do Docker Desktop ou modificar clusters existentes. **Custo/risco:** o node é privilegiado no daemon/VM Linux do Docker Desktop; permissões e filesystem dos Pods CredPay não isolam esse node do host de confiança. Consome CPU, memória, disco e rede; compatibilidade Windows/Docker Desktop ainda precisa ser comprovada. Não é ambiente de produção, AI-Jail ou rede com allowlist de egress.

## Escopo exato da exceção proposta

- Um node kind da baseline abaixo e seus componentes internos padrão; não autoriza modo privilegiado nos Pods CredPay.
- O [provider Docker do kind](https://github.com/kubernetes-sigs/kind/blob/v0.33.0/pkg/cluster/internal/providers/docker/provision.go) usa `--privileged`, seccomp/AppArmor sem confinamento, tmpfs em `/tmp` e `/run`, volume próprio em `/var` e `/lib/modules:ro` do host Linux do daemon. Os componentes internos mantêm os privilégios/mounts próprios já documentados no [recorte CI](../infra/k8s/README.md). Não prometer isolamento porque o Docker Desktop usa uma VM.
- Sem mounts extras de home, workspace, discos Windows, SSH ou socket nos nodes/apps. Drivers/configurações que exijam dispositivos/mounts adicionais param para nova análise; não ampliar silenciosamente a exceção.
- Ferramentas portáteis em `.local/k8s-tools/`; kubeconfig exclusivo em `.local/k8s/`, nunca no contexto padrão do usuário. Não imprimir/publicar kubeconfig, Secret, `.env`, inspect, logs brutos ou configuração resolvida.
- Configuração Docker de cliente isolada no workspace, sem credenciais reais, e endpoint Linux verificado explicitamente. Não encaminhar proxies/credenciais do ambiente ao node. Verificar o tratamento de proxy pelo provider antes de implementar o launcher; não supor que não há credenciais pelo simples fato de serem pulls públicos.
- Nome exclusivo `credpay-local-<identificador>`, existência prévia recusada, propriedade registrada e conferida antes de recriar Pods ou encerrar recursos. Não reutilizar o nome CI para contornar seus guards.
- API e port-forward somente127.0.0.1, sem Ingress/LoadBalancer/portas públicas. Sem alterações de firewall, acesso externo, serviço pago ou instalação global.
- Somente dados fictícios novos. A validação pode recriar os Pods próprios e escalar apenas os Deployments desse cluster. Após o ensaio, remover somente o cluster de teste cuja propriedade foi comprovada, descartando seus dados; sem prune, purge, wildcard, remoção de volumes Compose existentes ou alteração de outros projetos.

A autorização recebida inclui essas operações de teste e esse cleanup restrito. Ela não dispensa baseline, testes, revisão ou triagem de achados; não aceita automaticamente vulnerabilidades de qualquer imagem. Antes da execução, verificar os recursos disponíveis e o impacto no Docker Desktop.

## Baseline antes de qualquer download

| Componente | Versão / SHA256 |
|---|---|
| kind Windows amd64 portátil | v0.33.0; `4b22adaa135368c5a465d56bbd8e520cbea87272a06ca00b6078e7b81515c9fc` |
| kubectl Windows amd64 portátil | v1.36.4; `31947857be7583f3e4e6d889a3fb781ca813a45c20f11ccb3ef309714d01a9f3` |
| node Linux amd64 | `kindest/node:v1.36.4@sha256:099e049362a1526b2db71494e1947aae99bd16290d7c895f2b7ea312e3cbfaed` |

Necessidade: o kubectl1.32.2 encontrado anteriormente não é a baseline do cluster1.36.4, e os scripts atuais são guardados para CI/Linux. Downloads futuros ficam no workspace, com checksum antes de executar, sem alteração permanente de PATH. Mesmas imagens PostgreSQL/RabbitMQ pinadas e política Never dos manifests existentes; apps construídos da revisão registrada e carregados com identidade conferida.

Fontes conferidas em24/10: [release kind](https://github.com/kubernetes-sigs/kind/releases/tag/v0.33.0), [metadata oficial](https://api.github.com/repos/kubernetes-sigs/kind/releases/tags/v0.33.0), [checksum publicado kind Windows](https://github.com/kubernetes-sigs/kind/releases/download/v0.33.0/kind-windows-amd64.sha256sum), [checksum kubectl Windows](https://dl.k8s.io/release/v1.36.4/bin/windows/amd64/kubectl.exe.sha256), [quickstart Windows](https://kind.sigs.k8s.io/docs/user/quick-start/). O digest do asset kind coincide com o checksum publicado. Após autorização, binários preparados em `.local/k8s-tools` tiveram seus hashes conferidos. João executou somente versões cliente: kindv0.33.0/go1.26.7/windows-amd64 e kubectlv1.36.4/Kustomizev5.8.1. Nenhuma instalação global/PATH permanente ou cluster é comprovado por esses comandos.

## Trabalho após autorização

1. Adaptar o launcher e os guards para um perfil local explícito, sem relaxar o perfil CI. Testar identidade/propriedade/configuração/caminhos antes de mutações; TDD para os comportamentos novos. Scripts existentes não devem ser executados localmente por mera troca do nome do cluster.
2. Conferir preflight, versões, recursos, imagens e ausência de credenciais encaminhadas; baixar/verificar ferramentas somente na localização prevista.
3. João cria o cluster e executa a validação assistida: cache de imagens, serviços Ready, UID10001/mounts0 nos apps, probes, três PVCs, recriação da infraestrutura sem perda, ativação controlada, APROVADA/REJEITADA/replay e recuperação do mesmo pedido após parada do processador.
4. Conferir estado e propriedade antes do cleanup; registrar somente resumos sanitizados com SHA, versões, resultado e limites. Erro local não autoriza apagar dados, alterar mounts/privilégios ou trocar a baseline sem revisão.

A [opção rootless](https://kind.sigs.k8s.io/docs/user/rootless/) exige runtime/cgroups/delegação próprios ainda não preparados. Não será usada como alegação de segurança nem instalada como atalho nesta proposta. Se a exceção local não for escolhida, o aceite técnico do CI permanece e o local continua pendente; não será reclassificado como concluído.
