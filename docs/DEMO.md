# Demonstração e explicação do CredPay

O roteiro usa somente transações fictícias e a v1 existente. Compose/fluxo e Kubernetes foram comprovados no CI; executar no computador de João ainda depende de infraestrutura e autorização aplicáveis. Pesquisa de sandbox foi separada da v1 por João em18/10/2026, incompleta e sem aceite/liberação do core. Ensaio por João continua necessário ao fechamento do backend. Servidores Prometheus/Grafana foram adiados por João em16/10/2026; métricas e diagnóstico HTTP permanecem. CI verde não comprova que João consegue explicar o projeto: a apresentação precisa ser ensaiada por ele.

Antes do roteiro técnico, leia [Entenda o CredPay](ENTENDA_O_CREDPAY.md) para relacionar o exemplo de produto às escolhas de engenharia.

## Primeiro ensaio guiado com João

Estado em22/10/2026: João concluiu o preflight assistido no checkout373b6a1, enviando os resultados dos comandos. Execução dos aplicativos, entendimento e apresentação continuam sem aceite pessoal. Faça uma etapa por vez e registre dúvidas nas suas palavras.

### 1. Conferir o ambiente

No seu terminal PowerShell, na raiz do checkout, execute:

```powershell
$PSVersionTable.PSVersion
java -version
docker compose version
docker version --format '{{.Server.Os}}'
git rev-parse HEAD
```

Esperado: PowerShell7.3+, Java21, Compose2 e resposta `linux` do servidor Docker. Registre o SHA completo. Se algum comando falhar, pare essa etapa e informe somente qual comando falhou e a mensagem curta, sem logs/configurações completos. Se faltar um pré-requisito, parar e revisar origem, versão e impacto da instalação com João; não instalar automaticamente nem apagar dados para contornar uma falha.

Esses comandos são para você executar. A preparação deste guia não acessou o daemon do seu computador. O ensaio começa pelo Compose; isso não conclui o aceite Kubernetes/DockerDesktop nem autoriza node kind privilegiado local.

**Preflight assistido de 22/10:** retornos de João confirmaram PowerShell 7.6.7, Java 21.0.6, Compose 2.39.4-desktop.1 e servidor Docker respondendo `linux` no SHA `373b6a124f25bc582baffc44ae86f7b1dccc7dbb`. O PowerShell inicial era5.1; João instalou Microsoft.PowerShell7.6.7.0 viaWinGet e confirmou7.6.7 na sessão nova. Essa conferência atende aos pré-requisitos de versão principal; não declara todos os componentes do computador atualizados ou seguros.

O contexto desktop-linux falhou ao acessar seu named pipe, mesmo com o aplicativo mostrando Engine running. A consulta explícita ao contexto default retornou linux. João selecionou-o somente na sessão atual e repetiu a consulta com sucesso:

```powershell
$env:DOCKER_CONTEXT = 'default'
docker version --format '{{.Server.Os}}'
```

Essa variável vale apenas para a sessão e os processos iniciados por ela. Não é uma mudança permanente de contexto nem uma correção comprovada do endpoint desktop-linux. Ao abrir outro terminal, verificar novamente a conexão antes do roteiro. Nenhum aplicativo CredPay foi iniciado nessa conferência; ela não encerra o ensaio nem o aceite de execução local.

### 2. Explicar o produto antes de subir os serviços

Leia o exemplo de R$50/R$150 em [Entenda o CredPay](ENTENDA_O_CREDPAY.md), feche a página e explique em cinco frases:

1. O que o cliente pede e por que o POST responde PENDENTE.
2. Qual serviço decide e qual regra aplica.
3. Como o resultado volta e aparece no GET.
4. O que acontece ao repetir a mesma chave/pedido.
5. Qual situação de falha o projeto consegue demonstrar e qual limite conserva.

Se travar, registre o ponto e consulte a explicação correspondente; depois tente novamente com suas palavras. Esse exercício não exige decorar o texto de três minutos.

### 3. Executar a demo com pausas

Use a seção **Construir e preparar** do [quickstart Compose](../infra/compose/README.md), que é a referência dos comandos de build/configuração. Execute os comandos separadamente, parando em cada pausa da tabela; não cole o bloco inteiro de uma vez. Preserve `.env` existente e volumes; os exemplos são fictícios. Package com skipTests prepara artefatos, sem substituir as suítes do CI.

| Pausa | Ação no quickstart | O que conferir e explicar |
|---|---|---|
| Build | Preparar os dois JARs, conferir configuração e construir imagens com a mesma tag/SHA | Por que são dois aplicativos e por que tag/JARs precisam corresponder ao checkout |
| Prepare | Executar somente Prepare e aguardar sucesso | Infraestrutura/topologia/políticas prontas; consumo/publicação ainda não ativados |
| Activate | Executar somente Activate após Prepare | Consumidores e publicação ativados após conferência, com flags explícitos |
| Demo | Executar somente Demo | Exemplos50/150BRL convergem para APROVADA/REJEITADA; replay conserva resposta original PENDENTE/Location |
| Down | Executar Down ao terminar, inclusive se o ensaio ficar para depois | Recursos desse projeto param e os volumes permanecem; nenhum purge/prune |

Em qualquer falha, interrompa a sequência e guarde apenas etapa/comando/mensagem curta. Não pule Prepare, edite tabelas ou use screenshots antigos como execução atual. O roteiro imprime conclusões sem UUIDs/payloads/credenciais; compartilhe somente essas conclusões e dúvidas. Não publique `.env`, inspect, configuração resolvida ou logs integrais.

### 4. Defender decisões e limites

Responda às seis perguntas da tabela abaixo sem ler o roteiro, depois abra o código/evidência indicado e mostre onde a resposta é sustentada. Concentre-se em outbox/duplicatas, POST replay versus GET atual, concorrência, commit/ack, DLQ/FALHOU e limites atuais. Se não souber um ponto, registre-o como pendente; não invente experiência ou garantia.

A apresentação de três minutos vem depois dessa rodada. Grave ou apresente uma tentativa com suas palavras, confira as dúvidas e repita. Não chamar entrega pelo menos uma vez de exactly-once; DLQ não equivale a decisão financeira FALHOU. Os contadores medem tentativas, e CI não comprova execução pessoal.

### 5. Trazer um retorno curto

Preencha sem credenciais, IDs de transações ou dados pessoais:

```text
Data e SHA do checkout:
Ambiente: conferido / impedimento no comando ...
Build / Prepare / Activate / Demo / Down: executei / não executei / falhou
Fluxo em cinco frases: ...
Pergunta técnica que ainda não sei explicar: ...
Etapa seguinte que preciso praticar: ...
```

Pode guardar notas em `.local/ensaio/` (ignorado pelo Git). Não marcar uma etapa como executada só por ler o guia. O retorno permite orientar o próximo passo; aceite pessoal, Kubernetes e gates de segurança permanecem separados.

## Preparar a demonstração

1. Escolher um checkout revisado e conferir os checks do seu SHA. Os workflows de módulos, Flow, Images, Compose e Secret Scan têm responsabilidades diferentes; package com skipTests só prepara artefatos.
2. Conferir Java 21, PowerShell 7.3+, Compose 2 com up --wait e engine Docker Linux acessível. Se infraestrutura falhar, registrar o impedimento; não chamar isso de falha de negócio nem substituir execução por screenshot antigo.
3. Seguir o [quickstart Compose](../infra/compose/README.md): exemplo público fictício, JARs e imagens do mesmo SHA, Prepare antes de Activate. Não sobrescrever configuração local existente, trocar credenciais de volumes persistidos ou apagar dados para contornar erro.
4. Executar, na raiz e em PowerShell, depois do preparo/build descritos no quickstart:

```powershell
./infra/compose/demo.ps1 -Action Prepare
./infra/compose/demo.ps1 -Action Activate
./infra/compose/demo.ps1 -Action Demo
```

O roteiro deve conferir políticas/filas e consumidores antes do fluxo. Demo cria 50.000 e 150.000 BRL com limite 100.00: POST retorna PENDENTE e GET converge para APROVADA/REJEITADA; replay preserva resposta e Location originais. Ele imprime conclusões sem UUIDs, payloads ou credenciais. Usar as evidências do teste Images para explicar snapshots completos de cinco tabelas; Demo não prova sozinho toda a matriz de idempotência/concorrência.

5. Encerrar com `./infra/compose/demo.ps1 -Action Down`, preservando volumes. Para retomar: Prepare/Activate com mesmo projeto e configuração compatível. Smoke usa exclusivamente projeto descartável, recusa volumes prévios e comprova os mesmos registros após down/up; não usar o nome de um projeto com dados para essa prova.

## Demonstrar recuperação

O [runbook dos JARs](../infra/e2e/README.md) explica os cenários de FluxoCredPayE2E: estados finais, duplicatas/replay, parada controlada/reinício do processador, DLQ recuperada, retorno sem rota e confirmação causal controlada. Preparar ambos JARs e executar o teste dedicado exige Docker Linux. Não importar código do processador no teste do serviço de transações nem inserir resultado financeiro por SQL para encenar uma recuperação. O [diagnóstico HTTP](../infra/observability/README.md) explica os contadores e a prova de returned/intenção preservada/restauração para cada publicador; painel não é requisito dessa demonstração.

As evidências estão em spec §9.53–9.54, §9.96–9.98 e nos workflows. Parada controlada comprova indisponibilidade e reinício, sem provar crash abrupto em toda janela. Retry limitado tem prova nos listeners; DLQ não é consumida automaticamente. Replay manual desse teste é exclusivo do broker descartável, não ferramenta operacional autorizada para ambiente externo. Esgotar uma falha operacional conserva PENDENTE recuperável; FALHOU exige confirmação causal, com transporte comprovado somente na fixture controlada e autoridade operacional ainda bloqueada.

## Explicação de aproximadamente três minutos

“CredPay simula uma transação assíncrona com dois serviços Java, cada um dono do seu PostgreSQL. A API cria uma transação PENDENTE. O processador decide APROVADA ou REJEITADA pelo limite configurado; o resultado volta por RabbitMQ e atualiza o estado consultável com histórico.

O problema central foi consistência entre banco e mensageria. A criação grava transação e intenção de evento no mesmo commit local. O processador também grava decisão e sua outbox atomicamente. Os publicadores marcam a intenção somente após confirmação do broker e ausência de retorno por falta de rota. Uma queda entre publicar e marcar ainda pode causar duplicata: a entrega é pelo menos uma vez.

Por isso, idempotência existe em duas fronteiras. No HTTP, a mesma chave e pedido equivalente repetem a resposta original PENDENTE; GET mostra o estado atual. Nos consumidores, identidade e conteúdo são conferidos. Replay equivalente preserva o resultado, e conteúdo divergente não sobrescreve dados. Constraints e locks transacionais protegem chamadas concorrentes.

Valores usam BigDecimal. Equivalência monetária compara valor decimal, enquanto o snapshot conserva os dados originais. Instantes usam java.time; contratos que exigem nanos são persistidos com segundos e nanos, e o instante de aplicação do histórico é normalizado para micros do PostgreSQL. Precisão e equivalência têm regras explícitas.

Os listeners retornam depois do commit, permitindo ack após dados duráveis. Rejeições permanentes seguem para DLQ e falhas operacionais recebem retry limitado a três tentativas. Esgotar retry não inventa uma decisão financeira: a transação pode continuar PENDENTE. FALHOU exige confirmação causal: aplicação atômica, transporte e recuperação já foram comprovados em teste controlado, preservando a decisão financeira existente. Autoridade operacional continua bloqueada; a coordenação não demonstra sozinha irreversibilidade física da causa.

O CI prova fluxo completo, duplicatas, rollback, reentrega e recuperação nos cenários documentados. Imagens comprovam estados finais e replay; Compose também comprova preservação após down/up. Essas provas não cobrem todo crash possível.

Uso uma réplica publicadora por serviço e broker de nó único; não alego alta disponibilidade. Kubernetes teve fluxo e recuperação comprovados no CI, sem comprovar execução no meu computador. Diagnóstico HTTP usa contadores de tentativas, que não medem transações únicas nem provam commit. O painel foi adiado; apps non-root não equivalem ao sandbox do agente. O fechamento da v1 exige demonstrar o sistema e defender essas decisões com autonomia.”

## Perguntas para João responder com evidências

| Pergunta | Ponto que precisa explicar | Código/evidência |
|---|---|---|
| Por que usar outbox e ainda aceitar duplicatas? | Atomicidade local; janela confirmação/marcação; identidade estável no reenvio | [CriarTransacaoService](../transacoes-service/src/main/java/br/com/credpay/transacoes/application/CriarTransacaoService.java), [PublicarOutboxService](../transacoes-service/src/main/java/br/com/credpay/transacoes/application/PublicarOutboxService.java); spec §9.53–9.54 |
| Por que POST replay retorna PENDENTE e GET retorna APROVADA? | Resposta original da operação versus estado atual do recurso; chave equivalente/conflito | spec §9.45, §9.52 e §9.62; Flow e Images preservam resposta/Location e registros |
| Como impedir duas decisões concorrentes? | Locks por identidades, constraints, transação e replay/conflito explícitos | [RegistrarProcessamentoService](../processamento-service/src/main/java/br/com/credpay/processamento/application/RegistrarProcessamentoService.java), [AplicarResultadoService](../transacoes-service/src/main/java/br/com/credpay/transacoes/application/AplicarResultadoService.java) |
| O que ocorre entre commit e ack? | Dados já duráveis; reentrega exige consumidor idempotente; janela testada não é exactly-once | spec §9.37 e §9.48 e runbook Flow; testes de listeners |
| Por que DLQ não significa FALHOU? | Falha operacional e decisão financeira são distintas; recuperação permanece possível; aplicação causal controlada não autoriza encerramento operacional arbitrário | spec §9.54 e §9.90; recuperação e confirmação causal controlada |
| Quais limites existem hoje? | Publicador de réplica única, broker sem HA, execução local não comprovada, sandbox adiada e sem aceite; painel adiado e sinais HTTP com limites explícitos | README, plano §6 e backlog |

## Critério de encerramento da apresentação

João deve executar o quickstart, explicar as seis respostas apontando código/testes e identificar os limites atuais. Registrar dúvidas concretas para o próximo incremento. Não marcar aprendizado, Kubernetes, sandbox ou v1 como concluídos apenas porque este roteiro foi escrito. Análise de dependências/imagens e revisão de publicação continuam gates próprios.
