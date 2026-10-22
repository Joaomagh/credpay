# CredPay local com Compose

Cinco serviços: aplicativos non-root, PostgreSQL17.11 próprio por serviço e RabbitMQ4.3.5. Imagens/digests são os mesmos de `infra/images`; três volumes pertencem ao projeto Compose. Rabbit usa hostname/nodename estáveis para recuperar o volume. Bancos e broker não publicam portas; os dois HTTPs usam somente `127.0.0.1` (8080/8081 por padrão). Uma instância publicadora por aplicativo.

Pré-requisitos: Java21, PowerShell7.3+, Docker Linux e Compose2 com `up --wait`. No preflight assistido de22/10/2026, João confirmou PowerShell7.6.7, Java21.0.6, Compose2.39.4-desktop.1 e engineLinux acessível pelo contexto default. Os aplicativos ainda não foram executados localmente nesse ensaio. O CI registra suas próprias versões antes da prova real; nenhuma ferramenta é instalada pelo roteiro. O healthcheck usa curl do runtime TemurinNoble fixado nos Dockerfiles dos serviços; healthcheck e leitura HTTP/JSON UP foram comprovados no CI.

Se desktop-linux falhar por named pipe ausente, verificar primeiro se DockerDesktop iniciou. No ensaio de João, `docker --context default version --format '{{.Server.Os}}'` retornou linux; somente após essa confirmação foi definido `$env:DOCKER_CONTEXT = 'default'` na sessão do PowerShell. Não presumir que default seja a conexão correta em todo computador. Manter a sessão durante o roteiro ou verificar novamente ao abrir outro terminal. Essa seleção temporária não modifica o contexto permanente e não concede acesso Docker ao agente.

## Construir e preparar

Na raiz, em PowerShell7, copiar somente o exemplo fictício para configuração local ignorada. Não usar essas credenciais em serviço externo. Em volumes existentes, trocar variáveis de inicialização não troca automaticamente usuários/senhas do PostgreSQL ou Rabbit; revisar a configuração original, sem apagar volumes para corrigir acesso.

```powershell
if (-not (Test-Path infra/compose/.env)) { Copy-Item infra/compose/.env.example infra/compose/.env }
$env:CREDPAY_IMAGE_TAG = git rev-parse HEAD
Push-Location transacoes-service
./mvnw.cmd --batch-mode --no-transfer-progress -DskipTests package
if ($LASTEXITCODE -ne 0) { throw 'Package transações falhou' }
Pop-Location
Push-Location processamento-service
./mvnw.cmd --batch-mode --no-transfer-progress -DskipTests package
if ($LASTEXITCODE -ne 0) { throw 'Package processamento falhou' }
Pop-Location
docker compose --env-file infra/compose/.env -f infra/compose/compose.yaml -p credpay-demo config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Configuração inválida' }
docker compose --env-file infra/compose/.env -f infra/compose/compose.yaml -p credpay-demo build transacoes processamento
if ($LASTEXITCODE -ne 0) { throw 'Build das imagens falhou' }
./infra/compose/demo.ps1 -Action Prepare
./infra/compose/demo.ps1 -Action Activate
./infra/compose/demo.ps1 -Action Demo
```

Linux usa `./mvnw` no lugar de `./mvnw.cmd`; os demais comandos funcionam em `pwsh`. Não repetir a cópia sobre uma `.env` já configurada. A tag precisa ser o SHA completo do checkout que produziu os JARs. Build não executa testes; suites/Flow/Images continuam separados. `pull_policy: never` impede buscar imagens de aplicativos no registry.

`Prepare` interrompe somente os dois apps deste projeto, sobe infraestrutura saudável e reinicia apps em duas fases: flags false/exchanges produtoras/consumidores zero; depois topologias true, listener/publicação false. Só importa políticas existentes ausentes ou idênticas; política concorrente/operator policy falha sem sobrescrever. Confere flag, quatro filas quorum/argumentos/capacidade/DLX, exchanges e bindings exatos. Filas persistidas são conservadas, sem exigir que broker reutilizado esteja vazio. A importação não apaga mensagens ou recursos.

`Activate` exige preparo íntegro e consumidores zero antes de recriar somente os apps com consumo/publicação true. Aguarda dois consumidores exatos, ack obrigatório e prefetch10/1, sem consumidor de DLQ. Alterar flags exige recriação; `restart` sozinho não atualiza o ambiente. Inspeção não detecta mudanças posteriores e não é barreira de startup na aplicação.

`Demo` cria somente exemplos fictícios50.000/150.000 BRL, aguarda GET APROVADA/REJEITADA com limite100.00 e confere replay da resposta original PENDENTE/Location. Imprime somente conclusões; nenhum UUID, payload ou credencial. O teste das imagens conserva a prova completa de causalidade, cinco tabelas e ambas publicações; esse roteiro operacional não repete toda aquela matriz.

Para observar contadores reais, acrescente `-Diagnostics` a Prepare, Activate e Demo seguindo o [roteiro de diagnóstico](../observability/README.md). O override é opcional; a configuração padrão permanece sem métricas HTTP. A leitura compara tentativas confirmed antes/depois da demo nos mesmos processos, sem interpretá-las como transações únicas.

## Parar preservando dados

```powershell
./infra/compose/demo.ps1 -Action Down
```

Não usa `-v`: preserva os três volumes. Para subir novamente, usar `Prepare` e `Activate` com o mesmo projeto, tag/configuração compatível e sem mudar identidades dos bancos/broker. [Compose mantém volumes no down](https://docs.docker.com/reference/cli/docker/compose/down/); [dependências saudáveis](https://docs.docker.com/compose/how-tos/startup-order/) evitam iniciar apps antes da infraestrutura pronta.

`Smoke` exige projeto descartável sem containers ou volumes anteriores. Executa preparo/ativação/demo, down sem volumes, novo preparo/ativação e confere GET final e replay POST dos mesmos registros, além dos mesmos três nomes de volume. CI usa nome exclusivo por run/attempt e remove **somente esse projeto descartável**, incluindo os volumes criados pelo job, no fim. Não oferece purge local, prune, wildcard ou exclusão global.

Em falha, o roteiro interrompe a sequência e omite saída que poderia conter configuração. Consultar status e diagnóstico no ambiente local, mantendo consumo desligado; não publicar `compose config` resolvido, env, inspect ou logs integrais. Health Actuator pode chegar como bytes pela mídia vendor: Json aceita texto ou UTF-8 estrito, com controle em `check-json.ps1`; não converte bytes implicitamente para string. Primeiro CI registrou PowerShell7.6.6/Compose2.38.2 e encontrou essa falha de conversão; cleanup passou, diagnóstico/correção em spec9.63. O [Compose CI #2](https://github.com/Joaomagh/credpay/actions/runs/37255146642) comprovou as duas sequências, AP/REJ/replay e os mesmos registros/três volumes após down/up em167,92s; cleanup CI passou. O preflight local passou em22/10; execução dos aplicativos e demais pausas do ensaio continuam pendentes. Não implica crash/reentrega em toda janela, Kubernetes, HA, deploy externo ou AI-Jail. Rede bridge não é allowlist de egress; daemon/runner continuam na base de confiança.
