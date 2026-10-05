# Kubernetes local — B07.5a em preparação

Manifests e roteiro escritos, **sem cluster executado ou aceite antecipado**. O CI proposto está em `ci.pending.yaml`, fora de `.github/workflows`; não executa no push. Aguarda revisão e direção específica do Navigator antes da ativação.

## Resultado proposto

13 recursos: Namespace, ConfigMap, Secret fictício, cinco Services, dois Deployments e três StatefulSets. Bancos próprios/volume claim por banco, Rabbit com hostname do Pod estável (`rabbit-0`) e nodename `rabbit@rabbit-0`; três PVCs1Gi. Default StorageClass do cluster descartável precisa provisionar volumes. Recriar um Pod conserva o PVC; excluir o cluster kind não conserva os dados.

Apps usam JARs do mesmo SHA, carregados no cluster, imagePullPolicy Never, UID10001, sem mounts/token de service account, capabilities removidas e nenhum Pod CredPay privilegiado. Cada app recebe somente credenciais do próprio banco e do broker. Infra usa as imagens oficiais já aprovadas e seus entrypoints; isso não significa que todos os processos auxiliares executam non-root. Configuração/credenciais são somente exemplos públicos. Secret/base64 não é criptografia; não usar valores reais nesse manifesto ou publicá-los em logs. Services são internos, sem Ingress/LoadBalancer ou porta exposta do host.

Réplica1 e estratégia Recreate evitam sobreposição de publicadores; inicialmente topologias/consumo/publicação false. Readiness inclui readinessState/db/rabbit, escolha conservadora para o experimento; liveness inclui somente estado da aplicação. Indisponibilidade externa não deve provocar reinício pelo liveness. Startup usa o grupo liveness para dar tempo ao contexto. Readiness HTTP não interrompe listener AMQP; a ativação/recuperação exige o próximo incremento. Requests/limits são baseline didática, não dimensionamento de produção. [Spring Boot3.5 probes](https://docs.spring.io/spring-boot/3.5/reference/actuator/endpoints.html#actuator.endpoints.kubernetes-probes).

O roteiro aplica infraestrutura e aguarda Ready antes dos apps. Exige health dos dois grupos/UID/mounts/imagem correta, exchanges produtoras e zero filas/consumidores. Depois recria somente os três Pods da infraestrutura desse cluster descartável: identidade nova dos Pods, mesmos PVC/PV e snapshot hash do histórico Flyway (incluindo installed_on) dos dois bancos. Apps são escalados a zero/aguardados ausentes antes de recriar Rabbit e conferir exchanges recuperadas; assim RabbitAdmin não pode reconstruí-las. Depois os apps voltam a uma réplica/Ready. SQL somente leitura; nenhum evento ou resultado financeiro fabricado. Não prova POST/GET, política consumidor, crash em todas as janelas ou HA; quorum de nó único não é alta disponibilidade.

## Baseline proposta, sem instalação local

Necessidade concreta dentro do plano: criar cluster Kubernetes reproduzível no CI, manipular somente seus recursos e carregar imagens locais. Kind/kubectl novos entram somente nesse workspace descartável do job; sem instalação global/sudo, upgrade dos serviços ou gasto externo. Baselines consultadas em2026-10-04:

| Artefato | Versão/identidade |
|---|---|
| kind Linux amd64 | v0.33.0, SHA256 `aee6151561422756b764a4ae28e7f44cda5af5a9eead3cc9985112b1de8d8e0d` |
| kubectl Linux amd64 | v1.36.4, SHA256 `8b8f088da2dab964f853b38464033b1be15ede2839eca751482357c45abdd05a` |
| node Kubernetes | `kindest/node:v1.36.4@sha256:099e049362a1526b2db71494e1947aae99bd16290d7c895f2b7ea312e3cbfaed` |

Fontes: [release kind0.33.0](https://github.com/kubernetes-sigs/kind/releases/tag/v0.33.0), [metadata/digest do binário](https://api.github.com/repos/kubernetes-sigs/kind/releases/tags/v0.33.0), [checksum kubectl](https://dl.k8s.io/release/v1.36.4/bin/linux/amd64/kubectl.sha256), [carregamento de imagens/contexto próprio](https://kind.sigs.k8s.io/docs/user/quick-start/). O node escolhido está listado nessa release; kubectl tem a mesma versão. Binários devem ter hash conferido antes de executar; node é fixado por digest. Componentes internos do cluster pertencem à baseline kind, com downloads pela infraestrutura do runner; não há claim de ambiente offline/egress allowlist.

Ferramenta existente localmente: kubectl1.32.2/Kustomize5.5.0. Usada somente para renderizar os13 recursos, com kubeconfig explícito num caminho do workspace, sem leitura do contexto padrão ou acesso a servidor. Não instalar kind nem usar esse cliente para acessar cluster1.36.4. Engine Docker local indisponível; aprovação proposta é somente CI.

## Exceção necessária antes da execução

[AGENTS.md §5](../../AGENTS.md) determina: “Não usar modo privilegiado”. O [provider Docker do kind0.33.0](https://github.com/kubernetes-sigs/kind/blob/v0.33.0/pkg/cluster/internal/providers/docker/provision.go) usa `--privileged`, desativa seccomp/AppArmor do **node** e monta `/lib/modules:ro`. Não interpretar autorização genérica para Kubernetes como exceção dessa regra; rootless não comprova conformidade literal. Kubernetes1.36.4 também executa [kube-proxy privilegiado/hostNetwork/hostPaths internos do node](https://github.com/kubernetes/kubernetes/blob/v1.36.4/cmd/kubeadm/app/phases/addons/proxy/manifests.go). A exceção precisa abranger o node e os componentes internos padrão dessa baseline; não os Pods CredPay. Nenhuma execução dessa infraestrutura está autorizada ainda.

Proposta para aprovação: permitir **o node kind e os componentes internos padrão da baseline do workflow CredPay Kubernetes CI num runner hospedado/descartável**, com a baseline acima e comportamento padrão do provider. Sem mounts adicionais de workspace/home/socket, credenciais reais/do agente ou contexto externo. Checkout não persiste credenciais Git, job contents:read. Kubeconfig próprio em `.local/k8s/<id>.yaml`, nunca publicado/impresso. Nome exclusivo por run/attempt e marcador somente após conferir que o cluster não existia; cleanup limitado ao cluster criado pelo job. Teste pode recriar os três Pods de infraestrutura e escalar somente os Deployments CredPay desse cluster e seu cleanup encerra os dados descartáveis. Não autoriza node privilegiado no computador de João, implantação externa, Docker socket nos apps ou alegação de AI-Jail/isolamento do host. Mesmo com essa exceção, runner/daemon/node privilegiado continuam base de confiança e rede não é allowlist.

Após aprovação específica: ativar o workflow proposto, executar smoke real, tratar qualquer falha sem reduzir teste e só então integrar a PR. Sem aprovação, manter a proibição e escolher outra plataforma mediante desenho/verificação própria. Preparação de políticas/ativação/fluxo, observabilidade e sandbox continuam trabalhos restantes distintos.
