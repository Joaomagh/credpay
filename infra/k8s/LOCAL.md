# Kubernetes local no Windows — roteiro assistido

Perfil autorizado especificamente em [AGENTS§5](../../AGENTS.md) e [proposta](../../docs/KUBERNETES_LOCAL_PROPOSAL.md). Os comandos Docker são executados por João, não pelo agente. O perfil padrão dos verificadores continua `CI`; não definir GITHUB_ACTIONS nem usar um nome CI para executar localmente.

O launcher `local.ps1` oferece três ações, sem instalação global, alteração permanente de PATH/contexto, leitura de `.env` ou uso do kubeconfig padrão. Exige PowerShell7.3+, Windowsx64, Docker Desktop Linux no endpoint já conferido e os dois executáveis portáteis com os checksums da proposta.

## Antes de executar

- Aguardar integração/revisão e checks aplicáveis do código que será executado; controles offline não comprovam Docker Desktop/kind reais.
- JARs preparados e imagens dos aplicativos construídos com a mesma tag completa. Para o ensaio atual, o manifesto ignorado `.local/evidence/local-acceptance-prepared.json` identifica f412652 e os hashes dos dois JARs; não retaggear imagens antigas.
- Preflight observado por João: kind0.33.0/windows-amd64, kubectl1.36.4/Kustomize5.8.1, endpoint `npipe:////./pipe/dockerDesktopLinuxEngine`, overlayfs,12CPUs e12431081472bytes. O launcher reconfere Linux/amd64, driver overlayfs/overlay2, ausência de rootless/userns e identidade do daemon. O piso2CPU/4GiB só recusa capacidade evidentemente insuficiente; não é dimensionamento ou garantia de que4GiB comportem o ensaio.
- Manter a demo Compose existente; o cluster usa recursos próprios e API/port-forward loopback com portas dinâmicas. Não altera seus volumes ou os outros projetos do Docker Desktop.

## Uma ação por vez

Primeiro, na raiz do CredPay e somente após os checks:

```powershell
./infra/k8s/local.ps1 -Action Create -ImageTag f412652af18674c781f867d01240cf6012a35e4b
```

Cria uma sessão exclusiva `credpay-local-<data>-<nonce>`, recusa nome/diretório/sessão existentes, confere ferramentas e imagens, usa configuração Docker vazia própria e ambiente dos filhos por allowlist. Não herda proxies, credenciais, TLS/contextos alternativos ou opções experimentais do usuário. Pulls eventualmente necessários são públicos e pinados; erro não autoriza copiar credenciais para o node.

O node permanece privilegiado conforme a exceção; são conferidos imagem, labels, ID real do container, namespaces, dispositivos, mounts, tmpfs e porta API somente127.0.0.1. Propriedade só passa a `Owned` após conferir daemon/node e kubeconfig próprio com certificado embutido, sem plugins, arquivos de credencial externos ou TLS inseguro. As quatro imagens são carregadas; as imagens dos apps no runtime precisam ter os mesmos IDs do host. Isso não rehash os JARs dentro da imagem nem inventaria o node/infraestrutura.

Após o resumo de criação, executar separadamente:

```powershell
./infra/k8s/local.ps1 -Action Validate
```

Reconfere propriedade antes de cada fase e chama cache/startup/fluxo no perfil `Local`. Usa as mesmas provas de persistência/PVCs/probes/UID/mounts, preparo/ativação, APROVADA/REJEITADA/replay e parada/recuperação causal existentes no CI. O cliente kubectl é absoluto; a cópia de políticas usa origem relativa para evitar a interpretação de `C:` como recurso remoto. Cada cliente usa contexto/kubeconfig próprios; o port-forward é filho oculto e encerrado no finally. A validação mantém o cluster até a remoção explícita.

Ao concluir o ensaio, executar separadamente:

```powershell
./infra/k8s/local.ps1 -Action Remove
```

Remove somente o cluster cujo daemonID/nodeID/labels/imagem/mounts/API/kubeconfig ainda correspondem ao marcador. Recusa node substituído, nodes adicionais ou propriedade incompleta. Descarta os dados fictícios desse cluster; não faz prune, purge, remoção recursiva do workspace ou exclusão dos volumes Compose. Preserva os registros da sessão e retira somente o ponteiro ativo após confirmar a ausência do cluster.

## Falhas e limites

O Docker pode normalizar o node privilegiado acrescentando `label=disable` às opções seccomp/apparmor do kind. O guard aceita somente a lista completa original ou essa lista com a opção adicional exata, sem opções desconhecidas ou duplicadas. Isso descreve o node privilegiado já autorizado, sem alterar os Pods CredPay. Se uma criação anterior parou em `node-policy`, esta correção não promove seu registro `Creating`: não repetir Create, editar owner ou executar Validate/Remove. Reconferir a política em consulta somente leitura e preparar recuperação com identidade comprovada antes de qualquer mutação.

Criação parcial conserva `Creating`, sem adoção ou remoção automática por nome. Enviar somente a fase/mensagem curta; não editar o marcador, apagar volumes, forjar nome CI ou aumentar privilégios para contornar erro. Falha de carregamento após propriedade comprovada conserva `Owned`, permitindo cleanup restrito após conferir a identidade. Fonte, kubeconfig, Secret, inspect e logs brutos não devem ser publicados.

Os guards evitam operações acidentais em outro recurso; arquivos locais não são prova criptográfica de propriedade contra alguém que controla host/daemon. Não eliminam todas as janelas de concorrência. Host/VM/node continuam base de confiança; rede Docker não é allowlist de egress, Kubernetes de um node não é HA e essa execução não comprova AI-Jail ou entendimento pessoal.

Controles sem Docker, rede ou credenciais:

```powershell
./infra/k8s/check-local-safety.ps1
./infra/k8s/check-local-actions.ps1
```

O primeiro verifica guards e filhos PowerShell fictícios (argumentos literais, ambiente, exit/timeout). O segundo executa o ciclo e escritas reais em diretórios de fixtures ignorados, com transporte de comandos fictício sem fallback nativo. Nenhum deles cria cluster. CI executa esses controles em Windows/Linux antes da prova Kubernetes real do perfil CI. Evidência atual e aceites pessoais separados em `task.md` e `spec.md`.
