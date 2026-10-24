# Kubernetes local no Windows — roteiro assistido

Perfil autorizado especificamente em [AGENTS§5](../../AGENTS.md) e [proposta](../../docs/KUBERNETES_LOCAL_PROPOSAL.md). Os comandos Docker são executados por João, não pelo agente. O perfil padrão dos verificadores continua `CI`; não definir GITHUB_ACTIONS nem usar um nome CI para executar localmente.

O launcher `local.ps1` oferece Create/Validate/Remove, retomada explícita Resume e repetição restrita LoadImages, sem instalação global, alteração permanente de PATH/contexto, leitura de `.env` ou uso do kubeconfig padrão. Exige PowerShell7.3+, Windowsx64, Docker Desktop Linux no endpoint já conferido e os dois executáveis portáteis com os checksums da proposta.

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

No Windows, o launcher fornece `PATHEXT=.EXE` aos filhos para o PowerShell executar binários na mesma sessão e capturar stdout/exit. PowerShell pode acrescentar `.CPL` ao iniciar: somente a lista exata `.EXE` ou `.EXE;.CPL` passa no guard, e a entrada Local normaliza para `.EXE` antes dos comandos. Extensões herdadas/arbitrárias continuam recusadas. HOME e USERPROFILE dos filhos apontam apenas para o temp próprio da sessão, contendo também o cache de descoberta do kubectl; o ambiente permanente do usuário permanece inalterado. Cache legado `/.kube/cache/` no workspace foi preservado e ignorado, sem ler kubeconfig padrão nem apagar arquivos. Essa regra não ignora `/.kube/config`.

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

O Docker pode normalizar o node privilegiado acrescentando `label=disable` às opções seccomp/apparmor do kind. O guard aceita somente a lista completa original ou essa lista com a opção adicional exata, sem opções desconhecidas ou duplicadas. Isso descreve o node privilegiado já autorizado, sem alterar os Pods CredPay. A PR194/2fd3d58 corrigiu essa recusa após red1/70 e red2/72 encontrados na revisão; controles finais72+9, Windows/Linux e cluster CI40133435967 passaram. João reconferiu a política completa, daemon original e kubeconfig somente em leitura; não houve promoção do registro por essas consultas.

### Retomada da falha pós-criação conferida

Para o caso acompanhado de `node-policy`, uma consulta somente leitura deve identificar o ID completo do node. Depois da revisão/CI da retomada e com esse ID literal, João pode executar `./infra/k8s/local.ps1 -Action Resume -ExpectedNodeId <ID-completo-conferido>`; o marcador `<...>` não é um comando copiável. Não selecionar por nome, editar owner ou repetir Create.

Resume exige a sessão ativa e o registro original em Creating, mesmo daemon/nome/tag/kubeconfig, nodeId original ainda vazio e duas identidades de imagem. Reconfere ID fornecido, node único, política completa, kubeconfig loopback e fontes/aliases pinados existentes. Consulta `/readyz` via kubectl, com TLS e credenciais próprias do kubeconfig, timeout15s, sem imprimir configuração/credenciais. Só promove Owned no disco após essa resposta e nova conferência de propriedade; carrega as mesmas quatro imagens e compara os IDs dos apps no runtime. Não cria/deleta cluster, não baixa imagens, não muda aliases e não executa Validate automaticamente.

Qualquer recusa anterior à promoção mantém Creating. Falha de carga posterior mantém Owned e permite somente cleanup com a identidade novamente conferida. O marker legado não registra exit/fase do kind: readyz comprova prontidão atual e TLS, não sucesso histórico do comando. Essa recuperação é para a sessão própria acompanhada, sob confiança do host/daemon/arquivos; não comprova propriedade contra administrador malicioso nem elimina todas as corridas.

### Carga de imagens no Docker Desktop com índice OCI parcial

O kind0.33 importa arquivos com `--all-platforms`. No ensaio local, a exportação das quatro imagens passou, mas o TAR incluiu referências ausentes de outras arquiteturas e metadados; a importação falhou com content-missing. A carga Local exporta explicitamente `docker save --platform=linux/amd64` (API1.48+), em TAR GUID próprio no temp da sessão, antes de `kind load image-archive`. A conferência de identidade também exige `docker image inspect --platform=linux/amd64` (API1.49+): esse é o piso atual. Não altera versão/tag, não recompila apps, não muda o daemon e não baixa conteúdo ausente. API sem suporte deve falhar, sem fallback silencioso.

Create/Resume usam esse mesmo fluxo. Se a falha de carga já deixou a sessão Owned, após integração/revisão/CI executar separadamente `./infra/k8s/local.ps1 -Action LoadImages`. Essa ação reconfere propriedade, ID original do node e imagens; exporta somente amd64, reconfere propriedade/fontes antes da importação e verifica a identidade das quatro imagens como descrito abaixo. Mantém Owned e retorna para um Validate separado. Não cria/deleta cluster nem aceita Creating/Validated. Falha mostra fase export-amd64/archive-identity/image-import/runtime-ids; preserva sessão e arquivo, e uma importação parcial pode alterar o cache antes da recusa. Arquivos próprios ficam ignorados para diagnóstico, sem cleanup recursivo ou exclusão automática.

O Docker Desktop pode identificar a origem pelo digest de um índice multiplataforma; o CRI usa o digest da config da imagem selecionada. Eles não são comparados diretamente. Os IDs de origem dos apps continuam presos ao registro original, e os aliases de infraestrutura às fontes pinadas. O Docker seleciona amd64 por referência; o leitor TAR sem extração verifica o hash do manifesto selecionado e da config, associação única de tag, plataforma e lista de camadas. Só então exige igualdade configDigest=CRI.id nas quatro imagens. Quando a origem legada já retorna configDigest, também verifica os bytes da config pelo hash. IDs de índice não são aceitos como alternativa ao ID CRI.

Após sucesso, `image-proof.json` próprio registra atomicamente apenas contexto e caminho do TAR. O cache Local relê o arquivo, reconfere fontes/plataforma/hashes e os quatro IDs atuais; não confia numa lista editável de IDs esperados. Prova ausente/adulterada ou TAR ausente bloqueiam validação. Remove continua independente desse arquivo e exige somente propriedade/política conferidas. Arquivos/daemon/host continuam parte da confiança; isso prova identidade no recorte, não inventário de vulnerabilidades nem elimina todas as corridas.

Criação parcial conserva `Creating`, sem adoção ou remoção automática por nome. Validate/Remove/LoadImages continuam recusando Creating; Resume é explícito e exige as verificações acima. Enviar somente a fase/mensagem curta; não editar o marcador, apagar volumes, forjar nome CI ou aumentar privilégios para contornar erro. Falha de carregamento após propriedade comprovada conserva `Owned`, permitindo cleanup restrito após conferir a identidade. Fonte, kubeconfig, Secret, inspect e logs brutos não devem ser publicados.

Os guards evitam operações acidentais em outro recurso; arquivos locais não são prova criptográfica de propriedade contra alguém que controla host/daemon. Não eliminam todas as janelas de concorrência. Host/VM/node continuam base de confiança; rede Docker não é allowlist de egress, Kubernetes de um node não é HA e essa execução não comprova AI-Jail ou entendimento pessoal.

Controles sem Docker, rede ou credenciais:

```powershell
./infra/k8s/check-image-archive.ps1
./infra/k8s/check-local-safety.ps1
./infra/k8s/check-local-actions.ps1
```

O controle de arquivo lê TARs fictícios reais e verifica hashes, plataforma, associação e recusas, sem extração. O controle de segurança verifica guards e filhos PowerShell fictícios (argumentos literais, ambiente, exit/timeout). O controle de ciclo executa escritas reais em diretórios de fixtures ignorados e usa transporte de comandos fictício sem fallback nativo. Nenhum deles cria cluster. CI executa esses controles em Windows/Linux antes da prova Kubernetes real do perfil CI. Evidência atual e aceites pessoais separados em `task.md` e `spec.md`.
