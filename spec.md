# CredPay — Especificação Técnica Viva

> Fonte de verdade do sistema que existe hoje. Preencher somente com decisão tomada, contrato aceito ou comportamento comprovado. Planos futuros ficam em `CREDPAY_PLAN.md`; próximas ações ficam em `task.md`.

**Última atualização:** 2026-10-08

**Fase atual:** conclusão da v1 — qualidade, ambiente reproduzível e preparação de Kubernetes; observabilidade e sandbox ainda pendentes.

**Estado:** fluxo POST → duas outboxes → GET APROVADA/REJEITADA comprovado com JARs e imagens; idempotência, concorrência, histórico único, ack após commit, retry/DLQ, reinício e recuperação manual comprovados nos cenários registrados. Compose prepara e confere políticas antes de ativar consumo/publicação e preserva dados no down/up (PR #117). Flags seguem desligados por padrão; ativação exige o roteiro. Kubernetes está somente proposto na PR #118, sem execução. FALHOU tem direção de produto aprovada em 2026-10-08, mas mecanismo e testes pendentes (§9.70); observabilidade, sandbox verificável e fechamento de qualidade/portfólio continuam pendentes.

## 1. Contexto e limites atuais

CredPay é um laboratório de processamento assíncrono de transações, sem dinheiro ou integrações financeiras reais. Possui dois serviços independentes em monorepo:

- `transacoes-service`: recebe pedidos, valida regras de entrada, mantém o estado consultável e publica eventos;
- `processamento-service`: consome pedidos de processamento, decide o resultado e publica o evento correspondente.

**Implementado:** `transacoes-service` com CI, regras de domínio, PostgreSQL/Flyway e endpoints de criação e consulta. A criação persiste a transação `PENDENTE`, exige chave idempotente, distingue primeira criação, replay equivalente e conflito, e grava atomicamente um `TransacaoCriada` v1 na outbox. O produtor declara uma exchange RabbitMQ durável e pode publicar manualmente uma pendência, marcando-a somente após `ack` sem retorno. O `processamento-service` possui scaffolding independente, build reproduzível, health check HTTP, decisão `APROVADA`/`REJEITADA`, adapter JPA/Flyway para o snapshot e adapter JDBC para gravar a intenção `TransacaoProcessada` v1 atomicamente em PostgreSQL.

**Processamento implementado:** o processador exige valor e limite não nulos e estritamente positivos. A aplicação carrega limites externos por moeda; o caso de uso transacional grava decisão e intenção de saída atomicamente, reutiliza o registro no replay equivalente e serializa entradas concorrentes que compartilham `eventId` ou `transactionId`. A migration V2 cria a outbox própria com backfill dos resultados V1; o caso de uso de publicação lê uma pendência e a marca após `ack` sem return. Um scheduler opt-in de réplica única pode chamar esse caso de uso. O listener chama o registro somente quando topologia e listener são habilitados explicitamente; rejeição permanente, retry limitado e reentrega após perda de conexão entre commit/ack foram testados em PostgreSQL/RabbitMQ reais. A recusa da DLQ cheia, retenção e recuperação foram comprovadas na fixture, não em ambiente operacional. O adapter PostgreSQL do primeiro serviço é validado isoladamente e pelo fluxo HTTP completo; sua constraint monetária foi testada por SQL direto.

**Fluxo vertical e idempotência comprovados:** domínio final, estado/histórico atômicos, idempotência sequencial/concorrente e replay HTTP original. Parser/topologia/políticas e listener opt-in comprovam commit antes de ack, replay sem novo histórico/Clock, permanentes para DLQ sem retry e três tentativas operacionais com rollback real. Dois JARs reais percorrem POST → processador/duas outboxes → GET APROVADA/REJEITADA; duplicatas dos dois eventos e replay POST preservam cinco tabelas completas, resposta/Location original e GET final. Ativação manual exige conferência do runbook, flags padrão false. Recuperação/diagnóstico e mecanismo FALHOU (B06), Kubernetes local (B07) e critérios restantes de qualidade, segurança e demonstração (B08) continuam no backlog. Imagens/Compose já foram comprovados no CI. Coordenação entre múltiplas réplicas publicadoras e HA de produção não são capacidades atuais. Sandbox AI-Jail continua apenas documentado.

**Últimas entregas:** fluxo/recovery, imagens e Compose integrados (§9.52–9.54, §9.60–9.63); Checkstyle na PR #120, roteiro na #121, inventário na #122 e Tomcat na #123 (§9.66–9.69). Achados remanescentes, observabilidade, Kubernetes, sandbox e ensaio pendentes. As seções por incremento preservam a evidência histórica; próximo passo vigente em task.md.

## 2. Arquitetura vigente

### Contexto

```text
Cliente → transacoes-service ⇄ PostgreSQL
                    ↓ RabbitMQ ↑
          processamento-service ⇄ PostgreSQL
```

A mensageria é assíncrona, com consistência eventual e entrega pelo menos uma vez. O produtor usa idempotência na entrada HTTP, outbox transacional e confirms/returns no RabbitMQ. Os dois consumos idempotentes opt-in e o retorno do resultado já foram comprovados pelo fluxo real; a consulta converge para APROVADA/REJEITADA com histórico causal único.

### Responsabilidades e propriedade dos dados

| Componente | Responsabilidade | Dados próprios |
|---|---|---|
| transacoes-service | entrada, visão consultável, aplicação do resultado e publicação confiável | transações, chaves idempotentes, histórico e outbox |
| processamento-service | decisão idempotente e publicação do resultado | snapshots de processamento e outbox próprios |
| RabbitMQ | transporte e dead-lettering; retry limitado executado pelo consumidor; políticas mínimas de entrada/DLQ versionadas e testadas | mensagens, não fonte de verdade |

## 3. Stack e versões verificadas

| Área | Tecnologia | Versão fixada | Evidência |
|---|---|---:|---|
| Linguagem | Java | 21 | `java -version`: 21.0.6 |
| Framework | Spring Boot | 3.5.16 | parent fixado no `transacoes-service/pom.xml`; teste verde |
| Build | Maven Wrapper | 3.9.16 | `transacoes-service/mvnw.cmd --version` |
| Banco | PostgreSQL | 17.11 | testes de runtime, repository e fluxo HTTP persistente com imagem fixada por digest |
| Mensageria | RabbitMQ | 4.3.5 | exchange do produtor validada em Testcontainers com imagem fixada por digest |
| Infraestrutura de teste | Testcontainers | 1.21.4 | gerenciamento Spring Boot e teste PostgreSQL executado |

Substituir “alvo” por versão exata e comando de verificação quando o build existir.

### 3.1 Baseline aprovada do `transacoes-service`

Esta baseline define o scaffolding do primeiro serviço. Ela foi materializada e verificada em 2026-07-11, sem regra de negócio.

| Item | Decisão |
|---|---|
| Java | 21 LTS |
| Spring Boot | 3.5.16 |
| Build | Maven Wrapper com Maven 3.9.16 |
| `groupId` | `br.com.credpay` |
| `artifactId` | `transacoes-service` |
| Versão do artefato | `0.0.1-SNAPSHOT` |
| Packaging | `jar` |
| Package base | `br.com.credpay.transacoes` |

O serviço terá build independente, com `pom.xml` e Maven Wrapper próprios. O package base conterá a classe de aplicação e será a raiz do component scan. Packages de camadas não serão criados vazios; surgirão apenas quando um incremento exigir classes reais.

#### Dependências iniciais aprovadas

| Dependência | Escopo | Motivo para entrar no scaffolding |
|---|---|---|
| `spring-boot-starter-web` | principal | fornece Spring MVC e servidor HTTP embarcado para o futuro contrato REST e para o health check HTTP |
| `spring-boot-starter-actuator` | principal | fornece `/actuator/health`, verificação operacional exigida na fundação |
| `spring-boot-starter-test` | teste | fornece suporte de teste do Spring Boot, JUnit Jupiter e AssertJ para verificar o contexto e sustentar os próximos ciclos TDD |

Mockito poderá chegar transitivamente pelo starter de teste, mas somente será usado em fronteiras que dificultem teste unitário. Nenhuma versão transitiva será sobrescrita sem necessidade; o gerenciamento de dependências do Spring Boot será a referência inicial.

#### Estrutura mínima futura

```text
transacoes-service/
├── .gitignore
├── .mvn/
│   └── wrapper/
│       └── maven-wrapper.properties
├── src/
│   ├── main/
│   │   ├── java/br/com/credpay/transacoes/
│   │   │   └── TransacoesServiceApplication.java
│   │   └── resources/
│   │       └── application.yml
│   └── test/
│       └── java/br/com/credpay/transacoes/
│           └── TransacoesServiceApplicationTest.java
├── mvnw
├── mvnw.cmd
└── pom.xml
```

O teste inicial verificará apenas que o contexto mínimo sobe. Como scaffolding sem comportamento, ele não exige red prévio; o primeiro comportamento de negócio posterior seguirá red, green e refactor.

#### Comandos de verificação

Executados com sucesso em 2026-07-11:

```powershell
java -version
.\mvnw.cmd --version
.\mvnw.cmd -Dtest=TransacoesServiceApplicationTest test
.\mvnw.cmd package
.\mvnw.cmd spring-boot:run
Invoke-RestMethod http://localhost:8080/actuator/health
```

Resultados observados: Java 21.0.6, Maven 3.9.16, um teste executado sem falhas, package verde, JAR executável e health com estado `UP`.

O teste e o package emitiram warning de autoanexação do Mockito/Byte Buddy no Java 21. Nenhum mock foi escrito neste incremento. O warning não quebrou o build, mas deve ser resolvido ou conscientemente aceito antes de a JVM futura bloquear carregamento dinâmico de agentes.

#### Fora da baseline inicial

- PostgreSQL, driver JDBC, Spring Data JPA e Flyway;
- RabbitMQ e Spring AMQP;
- Testcontainers;
- Bean Validation e OpenAPI;
- Spring Security e Resilience4j;
- Lombok e DevTools;
- registry Prometheus e observabilidade completa;
- Dockerfile, Docker Compose e Kubernetes;
- endpoints de transação, DTOs, entidades e regras de domínio;
- `processamento-service`;
- parent POM ou agregador Maven na raiz;
- Checkstyle, análise de dependências, pipeline CI e imagens OCI.

### 3.2 Baseline aprovada do `processamento-service`

Esta baseline define o segundo serviço como um aplicativo Spring Boot independente, sem API de negócio, consumo RabbitMQ, persistência ou regra de processamento no scaffolding. Foi materializada e verificada em 2026-09-18. Reutilizar as versões já comprovadas reduz variáveis sem criar um parent POM compartilhado.

| Item | Decisão |
|---|---|
| Java | 21 LTS |
| Spring Boot | 3.5.16 |
| Build | Maven Wrapper com Maven 3.9.16 |
| `groupId` | `br.com.credpay` |
| `artifactId` | `processamento-service` |
| Versão do artefato | `0.0.1-SNAPSHOT` |
| Packaging | `jar` |
| Package base | `br.com.credpay.processamento` |

#### Dependências iniciais aprovadas

| Dependência | Escopo | Motivo para entrar no scaffolding |
|---|---|---|
| `spring-boot-starter-web` | principal | disponibiliza o servidor HTTP necessário ao health check operacional; não autoriza endpoint de negócio |
| `spring-boot-starter-actuator` | principal | expõe `/actuator/health` para verificar que o processo está pronto |
| `spring-boot-starter-test` | teste | fornece Spring Test, JUnit Jupiter e AssertJ para o smoke test de contexto e os próximos ciclos TDD |

O serviço terá `pom.xml` e Maven Wrapper próprios. Não haverá código compartilhado entre os serviços nesta etapa; contratos comuns só serão extraídos quando repetição e compatibilidade justificarem.

#### Estrutura mínima implementada

```text
processamento-service/
├── .gitignore
├── .mvn/
│   └── wrapper/
│       └── maven-wrapper.properties
├── src/
│   ├── main/
│   │   ├── java/br/com/credpay/processamento/
│   │   │   └── ProcessamentoServiceApplication.java
│   │   └── resources/
│   │       └── application.yml
│   └── test/
│       └── java/br/com/credpay/processamento/
│           └── ProcessamentoServiceApplicationTest.java
├── mvnw
├── mvnw.cmd
└── pom.xml
```

O smoke test inicia o servidor em porta aleatória e verifica `GET /actuator/health` com `200 OK` e estado `UP`. Como scaffolding sem comportamento, não exigiu red prévio.

#### Fora da baseline inicial

- Spring AMQP, RabbitMQ, filas, bindings, listener, retry e DLQ;
- PostgreSQL, JPA, Flyway e Testcontainers;
- regra de aprovação/rejeição, domínio, DTO, evento de saída e deduplicação;
- endpoints HTTP de negócio, OpenAPI, autenticação e autorização;
- Dockerfile, Docker Compose, Kubernetes, métricas Prometheus e tracing;
- parent POM/agregador, biblioteca compartilhada e dependências novas na raiz;
- workflow de CI próprio, que será um incremento posterior ao build local reproduzível.

#### Evidência do scaffolding

- Maven Wrapper 3.9.16 e Java 21.0.6 confirmados no Windows;
- teste focado e `verify` verdes, ambos com 1 teste, zero falhas, erros ou skips;
- JAR executável `processamento-service-0.0.1-SNAPSHOT.jar` gerado pelo Spring Boot Maven Plugin;
- o primeiro build sem acesso externo falhou ao resolver o parent ainda ausente no cache; a repetição autorizada baixou apenas as dependências aprovadas;
- o script Windows do Maven Wrapper 3.3.4 indexava `Target[0]` quando `~/.m2` era uma pasta comum. Uma guarda mínima para ausência de target corrigiu o bootstrap sem alterar a distribuição Maven nem reduzir validações;
- permanece o warning conhecido de autoanexação Mockito/Byte Buddy no Java 21, mesmo sem mocks escritos neste módulo.

## 4. Configuração e segredos

Cada serviço exige seu próprio PostgreSQL; os testes fornecem URL, usuário e senha fictícia dinamicamente, e a execução real deve recebê-los do ambiente. Não existe perfil sem persistência nem credencial padrão versionada. O `processamento-service` também exige limites por moeda; sua publicação RabbitMQ é opt-in.

| Serviço | Variável | Obrigatória | Valor padrão | Propósito | Sensível |
|---|---|---|---|---|---|
| transacoes-service | `SPRING_DATASOURCE_URL` | sim | nenhum | URL JDBC do PostgreSQL do serviço | não |
| transacoes-service | `SPRING_DATASOURCE_USERNAME` | sim | nenhum | usuário do banco | sim |
| transacoes-service | `SPRING_DATASOURCE_PASSWORD` | sim | nenhum | senha do banco | sim |
| transacoes-service | `SPRING_RABBITMQ_HOST` / `SPRING_RABBITMQ_PORT` | para mensageria e health completo | padrões Spring Boot | endereço AMQP do broker | não |
| transacoes-service | `SPRING_RABBITMQ_USERNAME` / `SPRING_RABBITMQ_PASSWORD` | configurar conforme o broker | padrões Spring Boot; definir no ambiente | autenticação no RabbitMQ | sim |
| transacoes-service | `CREDPAY_OUTBOX_PUBLISHER_ENABLED` | não | `false` | ativa scheduler em uma única réplica | não |
| transacoes-service | `CREDPAY_OUTBOX_PUBLISHER_INTERVAL` | não | `PT1S` | intervalo após terminar um lote e atraso inicial | não |
| processamento-service | `CREDPAY_PROCESSAMENTO_LIMITES_<MOEDA>` | ao menos uma moeda | nenhum | limite decimal positivo da moeda ISO 4217; exemplo fictício `CREDPAY_PROCESSAMENTO_LIMITES_BRL=100.00` | não |
| processamento-service | `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | sim | nenhum | conexão ao PostgreSQL próprio | usuário/senha: sim |
| processamento-service | `CREDPAY_OUTBOX_PUBLISHER_ENABLED` | não | `false` | ativa scheduler e indicador RabbitMQ em uma única réplica | não |
| processamento-service | `CREDPAY_OUTBOX_PUBLISHER_INTERVAL` | não | `PT1S` | intervalo após uma tentativa e atraso inicial | não |
| processamento-service | `SPRING_RABBITMQ_HOST` / `SPRING_RABBITMQ_PORT` | para publicação habilitada | padrões Spring Boot | endereço AMQP do broker | não |
| processamento-service | `SPRING_RABBITMQ_USERNAME` / `SPRING_RABBITMQ_PASSWORD` | configurar conforme o broker | padrões Spring Boot; definir no ambiente | autenticação no RabbitMQ | sim |

Valores reais nunca entram neste documento. `.env.example` usa placeholders; arquivos locais de segredo devem ser ignorados pelo Git.

### Contrato de limites por moeda do processador

Decisão de 2026-09-19, registrada antes do código e implementada em TDD no mesmo incremento: cada moeda configurada tem seu próprio limite em `credpay.processamento.limites.<codigo>`. Não há câmbio nem um limite global aplicado a moedas distintas.

| Item | Contrato |
|---|---|
| Tipo | mapa de código ISO 4217 para `BigDecimal`, sem arredondamento |
| Obrigatoriedade | ao menos uma moeda; ausência ou mapa inválido impede inicialização |
| Limite | número estritamente positivo; nenhum valor padrão |
| Código | validado com `Currency`, normalizado para maiúsculas; chaves recebidas pelo objeto que colidam após normalização são erro; precedência entre fontes continua sendo a do Spring |
| Consulta | retorna somente o limite da moeda solicitada; moeda nula ou não configurada lança `IllegalArgumentException` |
| Fronteira | configuração fica fora do domínio puro; `ProcessarTransacaoService` seleciona pela porta `LimitesProcessamento` e delega a decisão ao domínio |
| Exemplo didático | `BRL=100.00` e `USD=20.00`, sem representar política financeira real |
| Fora deste incremento | consumidor, retry/DLQ, persistência, mudança de status, conversão de moeda e atualização dinâmica de configuração |

O futuro consumidor não poderá tratar ausência de política como aprovação ou rejeição financeira. Seu tratamento operacional e confirmação de mensagem serão definidos e testados antes de habilitar o consumo. O contrato HTTP do produtor permanece aceitando moedas válidas; suporte à entrada não significa que já exista política de processamento para todas elas.

#### Evidências do binding e validação de configuração

- **Red inicial:** teste de duas moedas não compilou pela ausência de `LimitesProcessamentoProperties`; após binding mínimo, ficou verde, preservando `100.00/BRL` e `20.123/USD` sem arredondamento.
- **Red das validações:** oito falhas esperadas em doze casos por entradas indevidas, ausência de erro explícito ou consulta sem política.
- **Revisão dos testes:** removida configuração auxiliar que podia mascarar registro por component scan. Com a aplicação real e build limpo, seis assertions falharam e um caso não encontrou o bean; só então foi adicionado `@EnableConfigurationProperties` na aplicação. Três testes de consulta/colisão já estavam verdes.
- **Green final:** 12 testes de configuração e `verify` com 24 testes no módulo, sem falhas, erros ou skips; JAR gerado. O teste HTTP injeta limite fictício explicitamente.
- **CI:** [PR #60](https://github.com/Joaomagh/credpay/pull/60), [Processing Service CI #25](https://github.com/Joaomagh/credpay/actions/runs/35461368661) verde no Linux.
- **Verificações adicionais:** valor vazio e binding de `CREDPAY_PROCESSAMENTO_LIMITES_BRL` cobertos. Uma primeira fixture de ambiente usou nome de fonte inadequado; foi corrigida para `test-systemEnvironment` conforme a [documentação Spring Boot](https://docs.spring.io/spring-boot/reference/features/external-config.html). Essa falha de fixture não é contabilizada como red de negócio.
- **Limites:** sem dependência nova, câmbio, consumidor ou persistência. Avisos de falha de contexto são esperados nos testes negativos; o warning conhecido do agente Mockito permanece.

#### Caso de uso de decisão por moeda

`ProcessarTransacaoService.executar(valor, moeda)` consulta `LimitesProcessamento.limitePara(moeda)` e repassa valor e limite ao domínio `ProcessadorTransacao`. A implementação da porta é o objeto de configuração existente; a aplicação não importa a infraestrutura e o domínio continua sem Spring. A interface existe para essa fronteira concreta, sem factory, DTO ou repositório antecipado.

- **Contrato:** abaixo ou exatamente no limite da moeda resulta em `APROVADA`; acima resulta em `REJEITADA`. Moeda sem política/nula e valor inválido preservam as exceções existentes, sem produzir resultado financeiro.
- **Red:** compilação do teste falhou exclusivamente pela ausência de `ProcessarTransacaoService`.
- **Green:** 9 casos de integração leve, com configuração real e sem mocks: fronteiras BRL, mesmo valor em USD com resultado diferente, moeda ausente/não configurada e valor nulo/zero/negativo.
- **Suíte:** `mvnw.cmd --batch-mode --no-transfer-progress verify` executou 33 testes, sem falhas, erros ou skips; JAR gerado. POM e versões não mudaram.
- **CI:** [PR #61](https://github.com/Joaomagh/credpay/pull/61), [Processing Service CI #28](https://github.com/Joaomagh/credpay/actions/runs/35483647905) verde no Linux.
- **Limites:** não há endpoint, listener, deduplicação, banco, gravação de resultado ou evento de saída no processador. Retornar uma decisão não significa completar a transação assíncrona.
- **Próximo:** definir a baseline de processamento idempotente, resultado durável e confirmação da mensagem antes de implementar consumo RabbitMQ.

#### Snapshot imutável da decisão — B02.1

`ResultadoProcessamento` conserva o valor e sua escala decimal, moeda, limite efetivamente consultado e status derivado. `ProcessarTransacaoService` consulta `LimitesProcessamento` exatamente uma vez por decisão e entrega esse mesmo limite à factory de domínio; uma fonte de configuração mutável não pode fazer o status usar um limite e o resultado registrar outro.

- **Red:** os testes com as novas expectativas foram escritos primeiro; a compilação falhou em `testCompile` exclusivamente porque `ResultadoProcessamento` não existia e o caso de uso ainda retornava somente `StatusProcessamento`.
- **Green focado:** 12 testes em `ProcessarTransacaoServiceTest` e `ResultadoProcessamentoTest`, sem falhas, erros ou skips. A factory pública também rejeita moeda nula com `moeda deve ser informada`.
- **Regressão e build:** `mvnw.cmd --batch-mode --no-transfer-progress verify` executou 36 testes, sem falhas, erros ou skips, e gerou o JAR.
- **CI:** [PR #63](https://github.com/Joaomagh/credpay/pull/63), [Processing Service CI #31](https://github.com/Joaomagh/credpay/actions/runs/36800478555) verde no Linux, com Maven `verify`.
- **Comportamento comprovado:** depois de produzir `APROVADA` com limite `100.00`, alterar a fonte para `50.00` produz uma nova decisão `REJEITADA`, mas não modifica o snapshot anterior. Uma fonte alternante confirma uma única consulta e coerência entre limite/status.
- **Revisão:** P.O. e dev sênior aceitaram o slice; o coordenador revisou diff e suíte. O warning conhecido de autoanexação Mockito/Byte Buddy permanece.
- **Limites:** snapshot em memória não é replay, persistência, idempotência nem auditoria. Não há ID, instante, banco, listener ou evento de saída; POM e dependências não mudaram.
- **Próximo:** definir baseline de persistência própria, schema/precisão e primeiro round-trip PostgreSQL antes de adicionar qualquer dependência.

## 4.1 Modelo de ameaça do AI-Jail

### Objetivo e ativos protegidos

O AI-Jail deve reduzir o alcance de um agente ou processo executado no sandbox caso ele se comporte de forma incorreta, seja induzido por prompt injection ou execute uma ferramenta maliciosa. Os ativos protegidos são:

- arquivos fora do workspace do CredPay, incluindo outros repositórios e dados pessoais do host;
- credenciais, tokens, chaves SSH, arquivos de configuração sensíveis, variáveis de ambiente e sessões autenticadas do host;
- Docker socket, daemon Docker e recursos de outros containers;
- integridade e disponibilidade do host, do Docker Desktop e de sua VM;
- confidencialidade e integridade do workspace, limitando alterações ao que o incremento autorizou;
- serviços e redes locais ou remotos que não tenham sido explicitamente liberados.

O código-fonte do próprio workspace não é secreto para o sandbox: ele precisa ser legível e, durante incrementos autorizados, gravável. Backups, histórico Git e revisão de diff continuam necessários porque o sandbox não impede toda alteração indevida dentro desse workspace.

### Acessos permitidos

O desenho do sandbox poderá conceder somente:

- leitura do workspace montado e escrita nele quando exigida pelo incremento aprovado;
- diretórios temporários isolados e descartáveis necessários às ferramentas;
- ferramentas locais previamente incluídas na imagem e processos sem privilégio;
- rede negada por padrão; exceções de egress devem ter destino e finalidade explícitos e ser aplicadas por proxy ou firewall verificável;
- variáveis não sensíveis estritamente necessárias à execução.

Não são permitidos mounts da home, raiz do host, credenciais, Docker socket ou sockets equivalentes; modo privilegiado; capabilities adicionais sem justificativa; acesso genérico à rede local ou à internet; nem herança de segredos do ambiente do host.

### Ameaças consideradas

- leitura, cópia, modificação ou exclusão de arquivos fora do workspace;
- descoberta ou exfiltração de segredos por arquivos, variáveis, mounts, rede ou metadados acessíveis;
- escape do container por configuração insegura, Docker socket, excesso de capabilities, dispositivos ou falha da plataforma;
- elevação de privilégio dentro do container e abuso de binários `setuid`/`setgid`;
- acesso lateral ao host, à rede local, a outros containers ou a serviços de nuvem;
- egress não autorizado, inclusive DNS e conexões diretas que contornem uma allowlist;
- persistência fora dos mounts autorizados ou sobrevivência de processos após o descarte do sandbox;
- consumo abusivo de CPU, memória, processos ou disco capaz de degradar o host;
- comandos destrutivos ou mudanças fora do escopo dentro do workspace.

Estão fora deste modelo inicial ataques físicos, comprometimento prévio do host/Docker Desktop, vulnerabilidades desconhecidas no kernel, hypervisor ou firmware e proteção do conteúdo do workspace contra um processo que recebeu legitimamente escrita nele.

### Limites de confiança do Docker Desktop e do host

Docker Desktop, daemon Docker, sua VM Linux, kernel/hypervisor, sistema operacional do host e controles de rede externos pertencem à base confiável. O sandbox não os protege caso já estejam comprometidos e não constitui uma fronteira equivalente a uma máquina física separada. Uma vulnerabilidade de escape pode invalidar as restrições do container.

O usuário do host que inicia o container também permanece confiável: seus privilégios e a configuração de compartilhamento de arquivos determinam o alcance máximo possível. Usuário não-root, capabilities reduzidas e mounts mínimos diminuem impacto, mas não eliminam a confiança na plataforma. Rede Docker `bridge`, sozinha, não bloqueia egress nem implementa allowlist. As garantias documentadas valerão apenas para a versão e configuração efetivamente testadas do Docker Desktop/host.

### Critérios de testes negativos

O sandbox somente poderá ser chamado de verificado quando testes reproduzíveis demonstrarem que:

1. caminhos do host fora do workspace e arquivos sensíveis conhecidos não podem ser lidos, criados, alterados ou removidos;
2. home, configuração Git/SSH, credenciais de provedores e segredos do host não aparecem em mounts, variáveis ou locais convencionais;
3. Docker socket não existe no container e comandos contra o daemon do host falham;
4. o processo executa como usuário não-root, não consegue elevar privilégio e não possui capabilities além das aprovadas;
5. dispositivos e interfaces perigosas não estão disponíveis, e o modo privilegiado está desativado;
6. egress para destino não autorizado, acesso à rede local, a outros containers e a endpoints de metadados falham; destinos explicitamente permitidos funcionam somente pelo mecanismo definido;
7. limites configurados de CPU, memória, processos e armazenamento são observáveis e impedem expansão além do valor aprovado;
8. escrita persiste apenas nos mounts autorizados; dados temporários e processos desaparecem ao remover o sandbox;
9. tentativas controladas de alteração fora do escopo no workspace são detectáveis por `git status` e `git diff`.

Cada teste deve registrar comando, resultado esperado, resultado observado, versão/configuração da plataforma e evidência sem segredos. Falhar por ausência acidental de uma ferramenta ou por erro de DNS não prova uma política de bloqueio; o teste deve alcançar e validar o controle responsável pela negação.

### Riscos residuais

- falhas ou comprometimento do Docker Desktop, daemon, VM, kernel/hypervisor ou host podem permitir escape ou acesso aos ativos;
- um processo com escrita no workspace pode corromper ou apagar seu conteúdo, inclusive arquivos não relacionados ao incremento;
- dados legítimos do workspace podem ser exfiltrados para qualquer destino liberado, e allowlists não inspecionam necessariamente o conteúdo;
- dependências e ferramentas previamente incluídas podem conter código malicioso ou vulnerável;
- limites de recursos reduzem, mas não eliminam, negação de serviço contra o host;
- mudanças de versão ou configuração podem invalidar evidências anteriores e exigem repetição dos testes;
- testes negativos cobrem cenários conhecidos e não provam ausência de todas as rotas de escape.

## 4.2 Testes negativos planejados para o AI-Jail

Os testes desta seção definem antecipadamente como o contrato da ADR-003 será avaliado. Eles ainda não foram implementados nem executados. Um teste negativo tenta realizar, de forma controlada, uma ação que o sandbox deve proibir.

| ID | Propriedade avaliada | Tentativa controlada | Resultado esperado | Controle responsável |
|---|---|---|---|---|
| AJ-FS-01 | somente o workspace é exposto pelo host | procurar mounts adicionais e acessar um arquivo-canário criado fora do workspace exclusivamente para o teste | nenhum bind mount adicional é encontrado e o canário não pode ser acessado | lista mínima de mounts |
| AJ-FS-02 | raiz protegida e temporários isolados | gravar fora do workspace e do `/tmp`, executar diretamente um arquivo no `/tmp` e observar seu descarte | escrita e execução são negadas; conteúdo temporário desaparece com o container | raiz somente leitura e `/tmp` em `tmpfs` limitado com `noexec` |
| AJ-SEC-01 | ambiente sem credenciais herdadas | definir uma sentinela falsa apenas no host e procurar seu nome e valor no container | a sentinela não existe no ambiente do container | allowlist de variáveis de ambiente |
| AJ-PRIV-01 | processo sem privilégio | inspecionar UID, capabilities e `no-new-privileges` e tentar uma operação que exija privilégio | UID não-root, capabilities vazias, `no-new-privileges` ativo e operação negada | usuário não-root, `cap-drop=ALL` e `no-new-privileges` |
| AJ-HOST-01 | daemon, sockets e dispositivos do host isolados | procurar Docker socket, sockets adicionais e dispositivos não autorizados e tentar contato com o daemon | recursos ausentes e contato com o daemon impossível | ausência de mounts, sockets e dispositivos; modo não privilegiado |
| AJ-NET-01 | rede negada por padrão | tentar DNS, egress para destino controlado, acesso à rede local e a endpoints de metadados | todas as tentativas falham pelo bloqueio de rede configurado | modo de rede desativado ou controle equivalente verificável |
| AJ-RES-01 | recursos limitados | inspecionar cgroups e tentar exceder CPU, memória e processos dentro de cargas previamente limitadas | limites configurados são visíveis e impedem expansão além dos valores aprovados | limites de CPU, memória e PIDs |
| AJ-LIFE-01 | execução efêmera | criar canários no workspace e no `/tmp`, iniciar processo e recriar o sandbox | somente o canário autorizado no workspace persiste; temporário e processo desaparecem | único mount persistente e ciclo de vida efêmero |
| AJ-SCOPE-01 | alterações no workspace são detectáveis | criar uma alteração-canário autorizada e inspecionar o repositório | `git status` e `git diff` mostram a alteração | controles detectivos do Git |
| AJ-TOOLS-01 | somente ferramentas aprovadas estão disponíveis | inventariar executáveis e tentar instalar ou baixar uma ferramenta em runtime | inventário coincide com a lista aprovada e instalação falha pelos controles previstos | imagem mínima, usuário não-root, raiz somente leitura e rede negada |

Fixtures de teste nunca usarão arquivos pessoais, credenciais reais ou serviços de terceiros. Arquivos-canário, sentinelas de ambiente e destinos de rede serão falsos, descartáveis e criados especificamente para o teste. Testes de consumo de recursos terão limites externos e serão executados em sandbox descartável para não degradar o host.

### Contrato de evidências

| Campo | Conteúdo obrigatório |
|---|---|
| Identificação | ID, objetivo e data/hora UTC do teste |
| Artefato | commit testado e digest imutável da imagem |
| Plataforma | versões do host, Docker Desktop, Docker Engine e sistema do container ou VM |
| Configuração | configuração efetiva do container e hash do arquivo ou comando que a produziu |
| Preparação | pré-condições, fixtures falsas e destino controlado utilizados |
| Execução | comando executado com valores sensíveis removidos |
| Expectativa | resultado e controle que deveriam permitir ou bloquear a ação |
| Observação | saída relevante, código de saída e estado observado, sem segredos |
| Controle positivo | ação permitida equivalente e seu resultado, quando aplicável |
| Conclusão | `PASS`, `FAIL` ou `BLOCKED`, justificativa, limitações e riscos residuais |
| Responsabilidade | pessoa que executou e revisou a evidência |

`PASS` exige que a ação proibida falhe pelo controle previsto e que o controle positivo demonstre, quando aplicável, que o procedimento alcançou o comportamento avaliado. `FAIL` indica que a ação proibida funcionou, que o controle não estava ativo ou que o resultado contradisse a expectativa. `BLOCKED` indica que o teste não pôde chegar a uma conclusão por problema de ambiente, ferramenta ou preparação.

Ausência acidental de ferramenta, endereço inválido, erro genérico de DNS ou mensagem de erro isolada não provam bloqueio. A evidência deve identificar o estado ou mecanismo responsável pela negação. Mudanças na imagem, configuração, Docker Desktop, Engine, VM ou host invalidam a aplicabilidade automática de evidências anteriores e exigem nova execução dos testes afetados.

## 4.3 Baseline aprovada do `sandbox-core`

Esta seção registra a decisão documental final do primeiro estágio. A baseline está aprovada para orientar uma implementação futura, mas seus controles ainda não foram implementados nem testados. Tag, digest, permissões e limites efetivos deverão ser comprovados durante a implementação.

### Hipótese pendente de topologia

A topologia em que o Codex permanece como controlador fora do container e envia comandos para um worker restrito dentro dele é apenas uma hipótese. Ela ainda não é decisão aprovada porque não foi definido um mecanismo técnico que impeça o controlador de executar comandos diretamente no host e contornar o sandbox.

Essa hipótese não bloqueia a validação isolada do `sandbox-core`. O primeiro estágio pode validar controles de container sem afirmar que toda execução do Codex passa por ele. Escolher a topologia final e seu mecanismo de enforcement exigirá aprovação explícita e testes próprios.

### Imagem, identidade e recursos

| Item | Baseline aprovada | Validação pendente |
|---|---|---|
| Imagem-base | Debian slim | tag e digest imutável devem ser verificados na implementação |
| Identidade | UID/GID `10001:10001` | escrita e ownership no bind mount devem ser testados no Docker Desktop |
| CPU | 1 CPU | limite efetivo deve ser observado em cgroup |
| Memória | 1 GiB | limite efetivo deve ser observado em cgroup |
| Swap | desabilitado | memória e memória+swap devem produzir ausência efetiva de swap |
| Processos | 128 PIDs | limite efetivo deve ser observado e testado de forma controlada |
| `/tmp` | 256 MiB | `tmpfs`, descarte, tamanho e opções `noexec`, `nosuid` e `nodev` devem ser verificados |

Os valores são limites máximos da baseline, não reservas ou requisitos de desempenho. O bind mount do workspace não recebe cota simples de armazenamento por esses parâmetros e permanece como risco residual.

### Perfil diagnóstico e ferramentas mínimas

O `sandbox-core` é um perfil diagnóstico para validar mounts, identidade, filesystem, capabilities, rede, recursos e ciclo de vida. Ele não é ainda um ambiente de desenvolvimento.

| Grupo | Ferramentas da baseline | Finalidade |
|---|---|---|
| Shell | shell POSIX | executar comandos não interativos mínimos |
| Arquivos | `cp`, `mv`, `mkdir`, `find`, `stat` | manipular fixtures e inspecionar arquivos autorizados |
| Texto | `grep`, `sed`, `awk`, `sort`, `head`, `tail`, `wc`, `diff`, `xargs` | inspecionar conteúdo e produzir evidências simples |
| Identidade e ambiente | `id`, `env` | verificar usuário, grupo e allowlist de ambiente |
| Sistema | `mount`, `ps` | verificar mounts e processos visíveis |
| Integridade | `sha256sum` | identificar configuração e artefatos de teste |

O inventário efetivo da imagem deverá ser comparado com esta lista. Dependências transitivas trazidas pela imagem-base não serão consideradas automaticamente aprovadas. Git, Java, Maven, `curl`, `wget`, Docker CLI, Docker Compose, SSH, `sudo`, `su`, clientes de nuvem, gerenciadores de credenciais, gerenciadores de pacotes utilizáveis em runtime e outros runtimes ficam fora do `sandbox-core`.

O teste AJ-NET-01 precisará de uma forma controlada de realizar conexões. A escolha entre imagem diagnóstica derivada ou modo de execução separado permanece pendente; a ausência de cliente de rede no `sandbox-core` não será aceita como evidência de bloqueio.

### Perfis futuros

Git será considerado em um futuro perfil operacional quando o worker precisar inspecionar ou alterar um repositório. Java e Maven serão considerados em um futuro `sandbox-java` quando existir necessidade aprovada de compilar ou testar código Java. Cada inclusão exigirá aprovação do Navigator, novo inventário, análise de superfície, limites medidos e repetição dos testes negativos afetados.

### Decisões ainda pendentes

| Decisão | Estado |
|---|---|
| tag e digest da imagem Debian slim | verificar e aprovar no incremento de implementação |
| compatibilidade de `10001:10001` com o Docker Desktop | validar antes de considerar a identidade funcional |
| imagem ou mecanismo diagnóstico para AJ-NET-01 | escolher antes de executar o teste de rede |
| topologia Codex/controlador e mecanismo de enforcement | permanece hipótese e não bloqueia o `sandbox-core` isolado |
| perfil operacional com Git | aprovar somente quando necessário |
| `sandbox-java` com Java e Maven | aprovar somente quando houver necessidade de build Java |
| estratégia de dependências sem egress | decidir junto do futuro `sandbox-java` |

## 5. Estrutura do repositório

No estado atual:

```text
credpay/
├── transacoes-service/
│   ├── .mvn/wrapper/
│   ├── src/main/
│   ├── src/test/
│   ├── mvnw
│   ├── mvnw.cmd
│   └── pom.xml
├── skills/
├── AGENTS.md
├── CREDPAY_PLAN.md
├── spec.md
└── task.md
```

`transacoes-service/target/` é saída local de build e está ignorado pelo Git.

## 6. Contratos

### API HTTP

O contrato de criação foi aprovado em 2026-09-10 e passou a persistir em 2026-09-13. Há testes HTTP do happy path, persistência, falhas de leitura, validações de valor/moeda e rejeição das coerções escalares explicitadas abaixo. Isso não implica cobertura exaustiva de todas as entradas JSON. A criação síncrona confirma o recurso `PENDENTE`; o processamento assíncrono permanece planejado.

| Método e rota | Request/response | Erros | Teste de contrato |
|---|---|---|---|
| `POST /transacoes` | header `Idempotency-Key` UUID e JSON com `valor` e `moeda`; `201 Created`, `Location` e representação `PENDENTE` | `400` para header ausente/malformado ou corpo ilegível; `409` para chave reutilizada com payload diferente; `422` para domínio inválido | `TransacaoControllerTest` e `TransacaoHttpTest` |
| `GET /transacoes/{id}` | `200 OK` e representação persistida | `400` para UUID malformado; `404` para UUID válido ausente | `BuscarTransacaoServiceTest`, `TransacaoControllerTest` e `TransacaoHttpTest` |

#### Criação de transação

Request com `Content-Type: application/json`:

```json
{
  "valor": 10.00,
  "moeda": "BRL"
}
```

- `valor`: número JSON obrigatório e maior que zero, lido como `BigDecimal`; `10` e `10.00` são aceitos, mas textos como `"10.00"`, `"0"`, vazio ou espaços retornam `400` sem conversão automática;
- `moeda`: texto com código alfabético ISO 4217 obrigatório, em letras maiúsculas; números e booleanos JSON retornam `400` sem conversão automática para texto;
- o cliente não informa ID nem status;
- o cliente informa `Idempotency-Key` como UUID canônico, independente do ID da transação.

Resposta de sucesso:

- status `201 Created`, pois a transação passa a existir como recurso, embora seu processamento final seja assíncrono;
- header `Location: /transacoes/{id}`;
- `Content-Type: application/json`;
- ID gerado pelo servidor no formato UUID;
- status inicial `PENDENTE`.

```json
{
  "id": "7b8b61c2-9f63-4d74-9f8d-89cb52de0ed9",
  "valor": 10.00,
  "moeda": "BRL",
  "status": "PENDENTE"
}
```

O `201` não significa que a transação foi aprovada. Ele confirma a criação do recurso; `APROVADA` ou `REJEITADA` serão resultados posteriores do fluxo assíncrono.

O contrato de erros usa `Content-Type: application/problem+json` e os campos padrão `type`, `title`, `status`, `detail` e `instance`. Os cenários da tabela têm exemplos comprovados por `TransacaoHttpTest`, sem mocks:

| Situação | Status | `title` | `detail` esperado |
|---|---:|---|---|
| corpo ausente/nulo, JSON malformado ou tipo JSON incompatível | `400 Bad Request` | `Requisição inválida` | `corpo deve conter um JSON válido com valor numérico e moeda textual` |
| `valor` enviado como texto, inclusive vazio ou espaços | `400 Bad Request` | `Requisição inválida` | `corpo deve conter um JSON válido com valor numérico e moeda textual` |
| `moeda` enviada como número ou booleano | `400 Bad Request` | `Requisição inválida` | `corpo deve conter um JSON válido com valor numérico e moeda textual` |
| `valor` ausente ou nulo | `422 Unprocessable Entity` | `Transação inválida` | `valor deve ser informado` |
| `valor` igual ou menor que zero | `422 Unprocessable Entity` | `Transação inválida` | `valor deve ser maior que zero` |
| `moeda` ausente ou nula | `422 Unprocessable Entity` | `Transação inválida` | `moeda deve ser informada` |
| código de `moeda` inválido | `422 Unprocessable Entity` | `Transação inválida` | `moeda deve ser um código ISO 4217 válido em letras maiúsculas` |

A criação e a consulta por UUID válido são persistentes. Autenticação, OpenAPI e publicação de evento permanecem fora deste estágio.

#### Idempotência da criação

`POST /transacoes` exige o header `Idempotency-Key`, com UUID gerado pelo cliente e independente do ID da transação. Ausência, formato inválido, replay e conflito estão comprovados da camada MVC ao PostgreSQL real.

| Situação | Resultado esperado |
|---|---|
| header ausente ou vazio | `400 Problem Details`, título `Requisição inválida`, detalhe `Idempotency-Key deve ser informado` |
| header presente, mas não é UUID | `400 Problem Details`, título `Requisição inválida`, detalhe `Idempotency-Key deve ser um UUID válido` |
| primeira chave com payload válido | cria uma única transação e retorna `201`, `Location` e representação `PENDENTE` |
| mesma chave e mesmo valor/moeda | não cria outra transação; repete status, `Location` e representação originais |
| mesma chave e valor ou moeda diferente | `409 Problem Details`, título `Conflito de idempotência`, detalhe `chave de idempotência já utilizada com outro payload` |

Payload equivalente será comparado depois da leitura e validação: valores numericamente iguais por `BigDecimal.compareTo`, como `10` e `10.00`, e a mesma moeda são o mesmo pedido. Formatação JSON, ordem de campos e escala textual não criam conflito. O replay devolve a representação persistida pela primeira criação, inclusive sua escala original.

A chave e a transação deverão ser persistidas atomicamente. O PostgreSQL será a autoridade de unicidade, inclusive para requisições concorrentes; uma implementação baseada apenas em consulta seguida de inserção não satisfaz o contrato. Duas requisições concorrentes com a mesma chave e payload deverão convergir para uma transação; com payloads diferentes, somente o vencedor cria e a outra recebe conflito.

Na v1, a chave não expira nem pode ser reutilizada. Isso simplifica a garantia, mas permite crescimento contínuo do armazenamento; retenção e limpeza ficam como risco futuro. Chaves não serão incluídas em mensagens de erro, logs de payload ou resposta. Autenticação, escopo da chave por cliente, retry de infraestrutura e evento permanecem fora até existirem os respectivos componentes.

#### Consulta de transação por UUID

**Entrega documental:** [PR #30](https://github.com/Joaomagh/credpay/pull/30).

Request: `GET /transacoes/{id}`, sem corpo, onde `{id}` é um UUID sintaticamente válido gerado pelo serviço.

Para uma transação existente:

- status `200 OK`;
- `Content-Type: application/json`;
- os campos `id`, `valor`, `moeda` e `status` refletem o registro persistido, sem gerar nova identidade ou reiniciar estado.

```json
{
  "id": "7b8b61c2-9f63-4d74-9f8d-89cb52de0ed9",
  "valor": 10.00,
  "moeda": "BRL",
  "status": "PENDENTE"
}
```

Para UUID válido inexistente:

- status `404 Not Found`;
- `Content-Type: application/problem+json`;
- `type: about:blank`;
- `title: Transação não encontrada`;
- `detail: transação não encontrada`;
- `instance: /transacoes/{id}`.

O controller depende da porta `BuscarTransacao`; o serviço consulta `TransacaoRepository` dentro de `@Transactional(readOnly = true)` e mapeia domínio para um resultado de aplicação. Ausência é traduzida por erro explícito para o Problem Details; exceção de infraestrutura continua propagando e não vira `404`.

UUID existente e UUID válido ausente são cobertos em teste unitário, slice MVC e HTTP/PostgreSQL. Listagem, paginação, filtros, cache, lock, autenticação e atualização de estado ficam fora.

##### UUID malformado

Quando `{id}` não puder ser convertido para UUID, a API retorna:

- status `400 Bad Request`;
- `Content-Type: application/problem+json`;
- `type: about:blank`;
- `title: Requisição inválida`;
- `detail: id deve ser um UUID válido`;
- `instance` igual à rota recebida.

Esse erro representa sintaxe inválida e ocorre antes do caso de uso. O teste MVC verifica que `BuscarTransacao` não é chamado; o teste HTTP completo comprova o mesmo contrato externo sem exigir estado prévio no banco. Mensagens internas do conversor, nome de classe Java e stack trace não são expostos. UUID vazio, parâmetros extras, normalização textual e outros identificadores não entram neste incremento.

### Eventos

`TransacaoCriada` v1 é persistido na outbox junto da criação e publicado com confirmação pelo RabbitMQ. Ambos os consumidores idempotentes estão implementados com opt-in e ack após commit; fluxo final, reentrega e falhas estão comprovados nas seções 9.41–9.54. Ativação operacional exige conferência das políticas e topologias.

| Evento/versão | Produtor | Consumidor | Campos | Garantias |
|---|---|---|---|---|
| `TransacaoCriada` v1 | `transacoes-service` | `processamento-service` | envelope versionado e dados da transação `PENDENTE` | intenção atômica/outbox, publicação pelo menos uma vez com confirms/returns; consumidor idempotente/ack após commit |
| `TransacaoProcessada` v1 | `processamento-service` | `transacoes-service` | identidade de saída, causa e estado final | intenção junto do resultado/publicação confirmada opt-in; estado e histórico atômicos/idempotentes, ack após commit |

Envelope JSON aprovado:

```json
{
  "eventId": "6dc8d48d-5b20-4ee9-ac7e-832e421121aa",
  "eventType": "TransacaoCriada",
  "eventVersion": 1,
  "occurredAt": "2026-09-16T12:00:00Z",
  "correlationId": "7b8b61c2-9f63-4d74-9f8d-89cb52de0ed9",
  "data": {
    "transactionId": "7b8b61c2-9f63-4d74-9f8d-89cb52de0ed9",
    "amount": "10.00",
    "currency": "BRL",
    "status": "PENDENTE"
  }
}
```

- `eventId` é um UUID novo e identifica a mensagem para deduplicação; replay HTTP não cria outro evento.
- `eventType` e `eventVersion` são literais `TransacaoCriada` e `1`.
- `occurredAt` é um instante UTC ISO 8601 gerado pela aplicação na primeira criação.
- `correlationId` é igual ao ID da transação na v1. Não reutiliza nem expõe `Idempotency-Key`.
- `amount` é texto decimal para preservar valor e escala sem conversão binária; `currency` usa o código ISO 4217 e `status` é `PENDENTE`.
- Campos desconhecidos devem ser ignorados pelos consumidores. Remover, renomear, mudar tipo ou semântica exige nova versão; adição opcional compatível pode permanecer na v1 quando consumidores existentes continuarem válidos.
- O contrato não contém credenciais, dados pessoais, stack trace nem detalhes de persistência.

O evento representa um fato confirmado no banco, não um comando e não uma promessa de aprovação. A ordem global não é garantida. O consumidor tolera reentrega e deduplica por `eventId`, verificando equivalência sem sobrescrever uma decisão confirmada.

Contrato implementado para `TransacaoProcessada` v1 (exemplo, com consumo implementado):

```json
{
  "eventId": "42a06a3b-178b-49db-a08e-1f7dad9ebc38",
  "eventType": "TransacaoProcessada",
  "eventVersion": 1,
  "occurredAt": "2026-09-16T12:00:01.123456Z",
  "correlationId": "7b8b61c2-9f63-4d74-9f8d-89cb52de0ed9",
  "causationId": "6dc8d48d-5b20-4ee9-ac7e-832e421121aa",
  "data": {
    "transactionId": "7b8b61c2-9f63-4d74-9f8d-89cb52de0ed9",
    "status": "APROVADA"
  }
}
```

`eventId` reutiliza o `outputEventId` imutável do resultado; `causationId` é o `eventId` de `TransacaoCriada`; `correlationId` e `data.transactionId` são o ID da transação. `occurredAt` é o `processedAt` confirmado, em UTC e precisão de microssegundos, não o horário variável da publicação. `status` aceita somente `APROVADA` ou `REJEITADA`; falha técnica e ausência de política não geram esse evento. Valor, moeda, limite aplicado, chave HTTP e detalhes internos permanecem fora do payload mínimo: o consumidor já possui a transação e só precisa de sua transição final. Campos desconhecidos compatíveis podem ser ignorados; mudança incompatível exige nova versão.

## 7. Modelo e regras implementadas

| Regra | Casos/edge cases | Evidência automatizada |
|---|---|---|
| Uma transação válida nasce `PENDENTE` | fixture válida usa `10.00` e `BRL`; valores nulo, zero e negativo, além de moeda nula, são rejeitados | `TransacaoTest.criar_deveDefinirStatusPendente_quandoTransacaoForValida` |
| Uma transação não pode ser criada com valor zero | lança `IllegalArgumentException` com mensagem `valor deve ser maior que zero` | `TransacaoTest.criar_deveRejeitar_quandoValorForZero` |
| Uma transação não pode ser criada com valor negativo | o menor caso testado usa `-0.01`; lança `IllegalArgumentException` com a mesma mensagem da fronteira zero | `TransacaoTest.criar_deveRejeitar_quandoValorForNegativo` |
| Uma transação não pode ser criada com valor nulo | lança `IllegalArgumentException` com mensagem `valor deve ser informado`, antes de avaliar o sinal | `TransacaoTest.criar_deveRejeitar_quandoValorForNulo` |
| Uma transação não pode ser criada com moeda nula | lança `IllegalArgumentException` com mensagem `moeda deve ser informada` | `TransacaoTest.criar_deveRejeitar_quandoMoedaForNula` |
| Uma transação preserva seus dados monetários validados | mantém valor, escala decimal e moeda em campos finais, sem arredondamento; fixtures `10.00/BRL` e `123.456/USD` | `TransacaoTest.criar_devePreservarValorEMoeda_quandoTransacaoForValida` |
| Uma transação preserva sua identidade | recebe UUID explícito na fábrica e o conserva em campo privado final, sem setter; duas fixtures de ID | `TransacaoTest.criar_devePreservarId_quandoTransacaoForValida` |
| Uma transação não pode ser criada sem identidade | ID nulo lança `IllegalArgumentException` com mensagem `id deve ser informado` | `TransacaoTest.criar_deveRejeitar_quandoIdForNulo` |
| O happy path HTTP cria uma representação pendente | request `10.00`/`BRL`; retorna `201`, `Location`, UUID, valor, moeda e `PENDENTE` | `TransacaoControllerTest.deveCriarTransacaoPendente` |
| A criação HTTP exige chave idempotente válida | ausência e formato inválido retornam `400` sem chamar o caso de uso | `TransacaoControllerTest` |
| A criação HTTP preserva replay e rejeita conflito | mesma chave/payload repete resposta; payload diferente retorna `409` | `TransacaoHttpTest` |
| O processamento aprova valor positivo dentro do limite | exemplos `99.99` e `100.00` para limite `100.00` resultam em `APROVADA` | `ProcessadorTransacaoTest.processar_deveAprovar_quandoValorForMenorOuIgualAoLimite` |
| O processamento rejeita valor acima do limite | `100.01` para limite `100.00` resulta em `REJEITADA`; comparação ignora diferença de escala decimal | `ProcessadorTransacaoTest.processar_deveRejeitar_quandoValorForMaiorQueLimite` |
| O processamento exige valor | valor nulo lança `IllegalArgumentException` com mensagem `valor deve ser informado` antes da comparação | `ProcessadorTransacaoTest.processar_deveFalhar_quandoValorForNulo` |
| O processamento exige limite | limite nulo lança `IllegalArgumentException` com mensagem `limite deve ser informado` antes da comparação | `ProcessadorTransacaoTest.processar_deveFalhar_quandoLimiteForNulo` |
| O processamento exige valor positivo | zero, inclusive `0.00`, e negativo lançam `IllegalArgumentException` com mensagem `valor deve ser maior que zero` | `ProcessadorTransacaoTest` |
| O processamento exige limite positivo | `0`, `0.00` e `-0.01` lançam `IllegalArgumentException` com mensagem `limite deve ser maior que zero` | `ProcessadorTransacaoTest.processar_deveFalhar_quandoLimiteNaoForPositivo` |
| A decisão captura a política utilizada | snapshot imutável conserva valor, moeda, limite e status; fonte alterada depois não modifica resultado anterior e cada decisão consulta o limite uma vez | `ProcessarTransacaoServiceTest` e `ResultadoProcessamentoTest` |

### Evidência TDD — aprovação dentro do limite

- **Red inválido descartado:** a primeira tentativa parou na resolução do parent Maven por bloqueio de rede do sandbox e não chegou a compilar o teste.
- **Red válido:** com acesso às dependências já aprovadas, a compilação falhou somente pela ausência de `ProcessadorTransacao` e `StatusProcessamento`.
- **Green focado:** depois de criar os dois tipos mínimos, o teste parametrizado executou os casos abaixo e exatamente no limite, totalizando 2 testes verdes.
- **Suíte:** `mvnw.cmd --batch-mode --no-transfer-progress verify` executou 3 testes no módulo, sem falhas, erros ou skips, e gerou o JAR.
- **Evidência remota:** [PR #53](https://github.com/Joaomagh/credpay/pull/53); [Processing Service CI #4](https://github.com/Joaomagh/credpay/actions/runs/35415488950) verde em Java 21/Linux, com os mesmos 3 testes e JAR gerado.
- **Implementação mínima daquele ciclo:** `StatusProcessamento` continha apenas `APROVADA` e o processador retornava esse resultado sem comparar os parâmetros. A comparação foi adicionada no ciclo seguinte, quando um valor acima do limite exigiu `REJEITADA`.
- **Limites:** não há validação de nulo, valor não positivo ou limite inválido; não há moeda, configuração externa, evento, RabbitMQ ou persistência.

### Evidência TDD — rejeição acima do limite

- **Red:** depois de adicionar somente o cenário `100.01` contra limite `100.00`, a compilação falhou apenas porque `StatusProcessamento.REJEITADA` ainda não existia.
- **Green:** o enum recebeu `REJEITADA` e `ProcessadorTransacao` passou a retornar esse estado quando `valor.compareTo(limite) > 0`; o teste focado executou 3 casos sem falhas.
- **Regressão:** os valores `99.99` e `100.00` continuam `APROVADA`, tornando explícita a inclusão da fronteira no limite.
- **Suíte:** `mvnw.cmd --batch-mode --no-transfer-progress verify` executou 4 testes no módulo, sem falhas, erros ou skips, e gerou o JAR.
- **Evidência remota:** [PR #54](https://github.com/Joaomagh/credpay/pull/54); [Processing Service CI #7](https://github.com/Joaomagh/credpay/actions/runs/35418388563) verde em Java 21/Linux, com os mesmos 4 testes e JAR gerado.
- **Limites:** valor e limite nulos ainda não possuem erro de domínio explícito; valor não positivo, moeda, configuração externa, eventos, RabbitMQ e persistência permanecem fora.

### Evidência TDD — valor nulo no processamento

- **Red:** o novo teste executou 4 casos; somente valor nulo falhou porque `BigDecimal.compareTo` lançava `NullPointerException` em vez do erro de domínio contratado.
- **Green:** uma guarda anterior à comparação lança `IllegalArgumentException` com mensagem `valor deve ser informado`; o teste focado executou 4 casos sem falhas.
- **Suíte:** `mvnw.cmd --batch-mode --no-transfer-progress verify` executou 5 testes no módulo, sem falhas, erros ou skips, e gerou o JAR.
- **Evidência remota:** [PR #55](https://github.com/Joaomagh/credpay/pull/55); [Processing Service CI #10](https://github.com/Joaomagh/credpay/actions/runs/35418613909) verde em Java 21/Linux, com os mesmos 5 testes e JAR gerado.
- **Limites:** limite nulo e números não positivos ainda não possuem validação explícita; moeda, configuração externa, eventos, RabbitMQ e persistência permanecem fora.

### Evidência TDD — limite nulo no processamento

- **Red:** o teste focado executou 5 casos; somente limite nulo falhou porque `BigDecimal.compareTo` lançou `NullPointerException` em vez do erro contratado.
- **Green:** uma guarda posterior à validação do valor lança `IllegalArgumentException` com mensagem `limite deve ser informado`; os 5 casos focados ficaram verdes.
- **Suíte:** `mvnw.cmd --batch-mode --no-transfer-progress verify` executou 6 testes no módulo, sem falhas, erros ou skips, e gerou o JAR.
- **Evidência remota:** [PR #56](https://github.com/Joaomagh/credpay/pull/56); [Processing Service CI #13](https://github.com/Joaomagh/credpay/actions/runs/35418809104) verde em Java 21/Linux, com os mesmos 6 testes e JAR gerado.
- **Limites:** valor zero e negativo, limite não positivo, moeda, configuração externa, eventos, RabbitMQ e persistência permanecem fora.

### Evidência TDD — valor zero no processamento

- **Regra:** `0` e `0.00` falham com `IllegalArgumentException` e mensagem `valor deve ser maior que zero`; a escala não muda a validação.
- **Red:** 7 casos focados, com 2 falhas esperadas (`Expecting code to raise a throwable`); os zeros eram aprovados.
- **Green:** guarda `valor.signum() == 0`; 7 casos focados verdes e `verify` com 8 testes, zero falhas, erros ou skips e JAR gerado.
- **CI:** [PR #57](https://github.com/Joaomagh/credpay/pull/57), [Processing Service CI #16](https://github.com/Joaomagh/credpay/actions/runs/35458957242) com `Maven verify` verde no Linux.
- **Limites deste incremento:** valores negativos e limite não positivo ainda precisam de validação. Nenhuma dependência, integração ou configuração nova.

### Evidência TDD — valor negativo no processamento

- **Regra:** um valor negativo é entrada inválida e lança `IllegalArgumentException` com `valor deve ser maior que zero`; não se confunde com a decisão de negócio `REJEITADA` para um valor válido acima do limite.
- **Red:** `-0.01` produzia `APROVADA`; 1 falha esperada entre 8 casos focados pela ausência de exceção.
- **Green:** guarda ampliada de `signum() == 0` para `signum() <= 0`; 8 casos focados e `verify` com 9 testes verdes, sem falhas, erros ou skips e JAR gerado.
- **CI:** [PR #58](https://github.com/Joaomagh/credpay/pull/58), [Processing Service CI #19](https://github.com/Joaomagh/credpay/actions/runs/35459378244) verde no Linux.
- **Próximo:** exigir limite estritamente positivo antes de aplicar a decisão; não houve mudança de dependências ou integração.

### Evidência TDD — limite positivo no processamento

- **Red:** 11 casos focados, com 3 falhas esperadas por ausência de exceção para limites `0`, `0.00` e `-0.01`.
- **Green:** guarda `limite.signum() <= 0`, posterior às validações existentes, lança `IllegalArgumentException` com mensagem `limite deve ser maior que zero`; 11 casos focados verdes.
- **Suíte:** `mvnw.cmd --batch-mode --no-transfer-progress verify` executou 12 testes, sem falhas, erros ou skips, e gerou o JAR.
- **CI:** [PR #59](https://github.com/Joaomagh/credpay/pull/59), [Processing Service CI #22](https://github.com/Joaomagh/credpay/actions/runs/35459692425) verde no Linux.
- **Revisão:** preservadas as fronteiras abaixo, igual e acima do limite. Warnings existentes de carregamento dinâmico do agente Mockito permanecem; não houve alteração de dependências.
- **Próximo:** definir como fornecer limite e moeda por configuração, sem comparar valores de moedas diferentes nem presumir conversão cambial. Consumo RabbitMQ e persistência continuam fora deste incremento.

### Evidência TDD — estado inicial `PENDENTE`

- **Red:** `mvnw.cmd -Dtest=TransacaoTest test` falhou na compilação do teste porque `Transacao` e `StatusTransacao` ainda não existiam.
- **Green focado:** após criar somente `Transacao` e `StatusTransacao`, o mesmo comando executou 1 teste, com 0 falhas e 0 erros.
- **Suíte:** `mvnw.cmd test` executou 2 testes, com 0 falhas e 0 erros.
- **Implementação mínima:** `StatusTransacao` contém apenas `PENDENTE`; `Transacao.criar(valor, moeda)` define esse estado e `status()` permite observá-lo.
- **Limite daquele ciclo:** valor e moeda ainda não eram validados; a rejeição de zero foi adicionada no ciclo seguinte. Não há persistência nem API.

### Evidência TDD — rejeição de valor zero

- **Red:** após adicionar somente o novo teste, `mvnw.cmd -Dtest=TransacaoTest test` executou 2 testes e falhou apenas no caso zero com `Expecting code to raise a throwable`.
- **Green focado:** após adicionar a condição `valor.signum() == 0` em `Transacao.criar`, o mesmo comando executou 2 testes, com 0 falhas e 0 erros.
- **Suíte:** `mvnw.cmd test` executou 3 testes, com 0 falhas e 0 erros.
- **Erro de domínio atual:** `IllegalArgumentException` com mensagem `valor deve ser maior que zero`.
- **Limite daquele ciclo:** valor negativo ainda não era rejeitado; essa regra foi adicionada no ciclo seguinte. Valor `null` e moeda inválida continuam sem validação.

### Evidência TDD — rejeição de valor negativo

- **Red:** após adicionar somente o novo teste, `mvnw.cmd -Dtest=TransacaoTest test` executou 3 testes e falhou apenas no caso negativo com `Expecting code to raise a throwable`.
- **Green focado:** após alterar a condição de `valor.signum() == 0` para `valor.signum() <= 0`, o mesmo comando executou 3 testes, com 0 falhas e 0 erros.
- **Suíte:** `mvnw.cmd test` executou 4 testes, com 0 falhas e 0 erros.
- **Erro de domínio atual:** `IllegalArgumentException` com mensagem `valor deve ser maior que zero`.
- **Limite daquele ciclo:** valor `null` e moeda ainda não eram rejeitados; a validação de valor nulo foi adicionada no ciclo seguinte. Não há persistência nem API.

### Evidência TDD — rejeição de valor nulo

- **Red:** após adicionar somente o novo teste, `mvnw.cmd -Dtest=TransacaoTest test` executou 4 testes e falhou apenas no caso nulo: era esperada `IllegalArgumentException`, mas `valor.signum()` produziu `NullPointerException`.
- **Green focado:** após adicionar uma guarda de nulo antes da validação de sinal, o mesmo comando executou 4 testes, com 0 falhas e 0 erros.
- **Suíte e build:** `mvnw.cmd --batch-mode --no-transfer-progress verify` executou 5 testes, com 0 falhas e 0 erros, e gerou o JAR.
- **Erro de domínio:** `IllegalArgumentException` com mensagem `valor deve ser informado`.
- **Limite daquele ciclo:** moeda nula ainda não era rejeitada; essa validação foi adicionada no ciclo seguinte. Não há persistência nem API.

### Evidência TDD — rejeição de moeda nula

- **Red:** após adicionar somente o novo teste, `mvnw.cmd -Dtest=TransacaoTest test` executou 5 testes e falhou apenas no caso de moeda nula com `Expecting code to raise a throwable`.
- **Green focado:** após adicionar uma guarda de nulo para a moeda, o mesmo comando executou 5 testes, com 0 falhas e 0 erros.
- **Suíte e build:** `mvnw.cmd --batch-mode --no-transfer-progress verify` executou 6 testes, com 0 falhas e 0 erros, e gerou o JAR.
- **Erro de domínio:** `IllegalArgumentException` com mensagem `moeda deve ser informada`.
- **Limite daquele ciclo:** a transação ainda não armazenava valor ou moeda e não possuía ID ou timestamp. Não havia persistência nem API.

### Evidência TDD — happy path de `POST /transacoes`

- **Entrega:** [PR #10](https://github.com/Joaomagh/credpay/pull/10), com implementação, testes e documentação do happy path.
- **CI e merge:** [execução Linux #9](https://github.com/Joaomagh/credpay/actions/runs/34648072809) aprovada para `ff5076d`; merge confirmado em `f75f663`.
- **Red MVC:** após adicionar somente `TransacaoControllerTest`, a compilação falhou pela ausência de `TransacaoController`, `CriarTransacao` e `Resultado`.
- **Green MVC:** após criar o controller e a porta do caso de uso, o teste MVC isolado executou 1 teste, com 0 falhas e 0 erros, usando `@MockitoBean` para simular a aplicação.
- **Red aplicação:** `CriarTransacaoServiceTest` falhou na compilação pela ausência de `CriarTransacaoService`.
- **Green aplicação:** a implementação mínima criou o domínio, gerou um UUID e devolveu `PENDENTE`, sem repository ou infraestrutura; o teste focado executou 1 teste sem falhas.
- **Suíte e build:** Maven 3.9.16 com `verify` executou 8 testes, com 0 falhas e 0 erros, e gerou o JAR executável. O contexto Spring completo iniciou com o controller conectado ao caso de uso.
- **Smoke test do JAR:** uma chamada real a `POST /transacoes` retornou `201`, `Location: /transacoes/{uuid}`, `Content-Type: application/json` e o corpo esperado com estado `PENDENTE`.
- **Limite:** a resposta não é persistida, não pode ser consultada e não publica evento. Os erros HTTP `400` e `422` ainda não foram implementados.
- **Nota de ambiente:** nesta execução Windows, `mvnw.cmd` parou antes do Maven por uma falha do script ao avaliar `~/.m2`; as evidências foram repetidas com a distribuição Maven 3.9.16 já instalada pelo wrapper. O workflow Linux continua usando `./mvnw`.

### Evidência TDD — resposta HTTP para valor zero

- **Entrega:** [PR #11](https://github.com/Joaomagh/credpay/pull/11), com tratamento HTTP e teste sem mocks.
- **CI e merge:** [execução Linux #12](https://github.com/Joaomagh/credpay/actions/runs/34648508957) aprovada para `ee15881`; merge confirmado em `27c3b18`.
- **Red:** `mvnw.cmd -Dtest=TransacaoHttpTest test` executou 1 teste com 1 erro: `ServletException` causada pela `IllegalArgumentException` do domínio, ainda sem tradução HTTP.
- **Green:** `TransacaoExceptionHandler` traduz `IllegalArgumentException` em `ProblemDetail` com status `422` e título `Transação inválida`; o teste focado passou.
- **Aceite comprovado:** JSON com `valor: 0` e `moeda: BRL` produz `application/problem+json`, `type: about:blank`, `status: 422`, `detail: valor deve ser maior que zero`, `instance: /transacoes` e nenhum `Location`.
- **Integração:** o teste usa `@SpringBootTest` e `MockMvc`, com controller, caso de uso e domínio reais, sem mocks; não abre uma porta de rede.
- **Suíte:** `mvnw.cmd --batch-mode --no-transfer-progress verify` executou 9 testes sem falhas e gerou o JAR. O wrapper funcionou no ambiente autorizado, sem mudanças no script.
- **Limite daquele ciclo:** o handler está restrito ao controller de transações, mas captura a categoria `IllegalArgumentException`; outros argumentos inválidos podem passar por ele. Isso não comprova os demais cenários do contrato. Moeda ausente foi tratada no ciclo seguinte.

### Evidência TDD — resposta HTTP para moeda ausente ou nula

- **Entrega:** [PR #12](https://github.com/Joaomagh/credpay/pull/12), com teste parametrizado e reutilização da validação de domínio.
- **CI e merge:** [execução Linux #15](https://github.com/Joaomagh/credpay/actions/runs/34649016292) aprovada para `cebb0b0`; merge confirmado em `4d4e8a7`.
- **Red:** o teste parametrizado enviou `{"valor":10.00}` e `{"valor":10.00,"moeda":null}`; ambos produziram `ServletException` causada por `NullPointerException` em `Currency.getInstance(null)`. O cenário de valor zero continuou verde.
- **Green:** o controller preserva a ausência da moeda como `null` ao chamar o caso de uso; o domínio aplica a validação existente e o handler retorna `422` com `detail: moeda deve ser informada`.
- **Evidência:** `mvnw.cmd -Dtest=TransacaoHttpTest test` executou 3 casos sem falhas; `mvnw.cmd --batch-mode --no-transfer-progress verify` executou 11 testes sem falhas e gerou o JAR.
- **Limite:** código de moeda inválido ainda exige mensagem segura e teste próprio. Não houve nova dependência, persistência ou mudança na regra de domínio.

### Evidência TDD — resposta HTTP para código de moeda inválido

- **Entrega:** [PR #13](https://github.com/Joaomagh/credpay/pull/13); [CI Linux #17](https://github.com/Joaomagh/credpay/actions/runs/34718184490) verde para `f40bdc6`; merge em `83018d8`.
- **Red:** `mvnw.cmd -Dtest=TransacaoHttpTest test` executou 7 casos; os 4 novos (`ZZZ`, vazio, `brl` e ` BRL `) falharam por ausência de `$.detail`. Os 3 anteriores passaram.
- **Green:** o adaptador HTTP traduz somente a falha de `Currency.getInstance` para uma mensagem estável e acionável. Não há normalização silenciosa de espaços ou caixa; moeda ausente continua sendo validada pelo domínio.
- **Verificação:** teste focado com 7 casos verdes; `mvnw.cmd --batch-mode --no-transfer-progress verify` com 15 testes sem falhas e JAR gerado.
- **Revisão:** mensagem não inclui o valor enviado nem detalhes internos da JVM; `pom.xml` e domínio não mudaram. O warning conhecido de autoanexação Mockito/Byte Buddy permanece.
- **Limite:** a validação usa o catálogo de moedas do JDK, não uma lista de moedas comercialmente suportadas. Erros de leitura JSON serão tratados no próximo incremento.

### Evidência TDD — resposta HTTP para corpo ilegível

- **Entrega:** [PR #14](https://github.com/Joaomagh/credpay/pull/14); [CI Linux #19](https://github.com/Joaomagh/credpay/actions/runs/34718467849) verde para `3e34747`; merge em `06441d1`.
- **Red:** teste HTTP com 12 casos, 5 falhas `Content type not set`. Corpo vazio, JSON literal `null`, JSON incompleto e objetos no lugar de valor/moeda retornavam `400` sem representação Problem Details no MockMvc.
- **Green:** o advice existente trata `HttpMessageNotReadableException` com `400`, título `Requisição inválida` e mensagem fixa; não devolve mensagem do parser, stack trace ou conteúdo do pedido.
- **Verificação:** `mvnw.cmd -Dtest=TransacaoHttpTest test` com 12 casos verdes; `mvnw.cmd --batch-mode --no-transfer-progress verify` com 20 testes verdes e JAR gerado.
- **Revisão:** tratamento limitado ao controller de transações; sem alteração no domínio, dependências ou `pom.xml`. Warning conhecido Mockito/Byte Buddy permanece.
- **Limite:** os testes cobrem falhas de leitura, não impõem ainda uma política estrita para todas as coerções escalares do Jackson. Cobertura HTTP de valor negativo/ausente/nulo e sucesso com contexto real será concluída no próximo incremento.

### Revisão e regressão — contrato HTTP com contexto real

- **Entrega:** [PR #15](https://github.com/Joaomagh/credpay/pull/15), com testes de regressão e atualização dos documentos.
- **CI e merge:** [CI Linux #22](https://github.com/Joaomagh/credpay/actions/runs/34718913568) verde para `ee2b7e5`; merge em `9ca45af`.
- **Objetivo:** cobrir criação válida e valor negativo/ausente/nulo com controller, caso de uso e domínio reais. O teste MVC isolado continua cobrindo a fronteira separadamente.
- **Resultado:** os novos casos passaram na primeira execução; são testes de regressão de comportamento existente, não um novo ciclo red/green. Nenhum código de produção foi alterado.
- **Sucesso:** `201`, valor/moeda/estado esperados, UUID canônico e `Location` contendo exatamente o ID da resposta.
- **Erros:** `422` com Problem Details completo e sem `Location`; mensagens `valor deve ser informado` e `valor deve ser maior que zero` conforme o caso.
- **Verificação:** teste HTTP focado com 16 casos verdes; Maven Wrapper `verify` com 24 testes verdes e JAR gerado. `pom.xml` e `src/main` inalterados.
- **Revisão periódica:** falta tornar explícita a rejeição de valor monetário textual no JSON, sem coerção silenciosa. Persistência e consulta continuam ausentes; warnings Mockito/Byte Buddy permanecem registrados.

### Evidência TDD — valor monetário textual no JSON

- **Entrega:** [PR #16](https://github.com/Joaomagh/credpay/pull/16); [CI Linux #24](https://github.com/Joaomagh/credpay/actions/runs/34719823665) verde para `1839eea`; merge em `c0f2ac7`.
- **Red:** `mvnw.cmd -Dtest=TransacaoHttpTest test` executou 20 casos; os 4 novos falharam: `"10.00"` retornava `201`, enquanto `"0"`, vazio e espaços retornavam `422` após conversão para número ou nulo.
- **Green:** `api/JacksonConfiguration` customiza o mapper gerenciado pelo Spring para rejeitar `String` e `EmptyString` ao desserializar `BigDecimal`. O handler existente traduz a falha de leitura para `400 Problem Details` com mensagem segura.
- **Regressão:** número inteiro `10` e decimal `10.00` continuam retornando `201`; ausência/nulo e números não positivos mantêm suas respostas `422`.
- **Verificação final:** teste HTTP focado com 21 casos verdes; Maven Wrapper `verify` com 29 testes sem falhas e JAR gerado. `pom.xml`, controller e domínio não mudaram; nenhuma dependência foi adicionada.
- **Trade-off:** a configuração se aplica a todo `BigDecimal` desserializado pelo mapper gerenciado deste serviço, não apenas ao campo atual. Não altera serialização, cálculo, escala ou arredondamento. Futuras entradas `BigDecimal` devem seguir esse contrato ou declarar uma exceção testada.
- **Revisão:** números/booleanos no campo `moeda` ainda exigem ciclo próprio. O warning conhecido Mockito/Byte Buddy permanece.
- **Referência:** [API de coerção Jackson](https://www.javadoc.io/static/com.fasterxml.jackson.core/jackson-databind/2.19.2/com/fasterxml/jackson/databind/cfg/MutableCoercionConfig.html); comportamento comprovado com as dependências já fixadas pelo projeto.

### Evidência TDD — moeda não textual no JSON

- **Entrega:** [PR #17](https://github.com/Joaomagh/credpay/pull/17), com rejeição de coerção de moeda e testes HTTP.
- **CI e merge:** [CI Linux #27](https://github.com/Joaomagh/credpay/actions/runs/34720037972) verde para `0835b0a`; merge em `91a52fc`.
- **Red:** teste HTTP com 25 casos; os 4 novos (`123`, `1.5`, `true`, `false`) falharam por retornar `422` em vez de `400`, após conversão automática para texto.
- **Green:** a configuração Jackson rejeita coerções de `Integer`, `Float` e `Boolean` para `String`. O handler de leitura existente retorna `400 Problem Details`, sem incluir o valor recebido ou mensagens internas.
- **Verificação:** `mvnw.cmd -Dtest=TransacaoHttpTest test` com 25 casos verdes; `mvnw.cmd --batch-mode --no-transfer-progress verify` com 33 testes verdes e JAR gerado.
- **Regressão:** `BRL` permanece aceito; moeda ausente/nula e códigos textuais inválidos mantêm `422`; a rejeição de valor textual segue ativa. `pom.xml`, domínio e controller não mudaram.
- **Trade-off:** aplica-se aos campos `String` lidos pelo mapper gerenciado do serviço. Futuras entradas textuais devem respeitar esse contrato. Não muda serialização nem introduz validação de negócio no parser.
- **Revisão periódica:** o domínio ainda valida valor/moeda sem conservá-los na instância; o caso de uso devolve esses dados diretamente da entrada. O próximo incremento preservará os dados validados na transação, com TDD e sem banco. Warning Mockito/Byte Buddy permanece conhecido.

### Evidência TDD — preservação dos dados monetários no domínio

- **Entrega:** [PR #18](https://github.com/Joaomagh/credpay/pull/18), com domínio, refatoração do caso de uso, testes e documentação.
- **CI e merge:** [CI Linux #30](https://github.com/Joaomagh/credpay/actions/runs/34732546413) verde para `aed6280`; merge em `dc24122`.
- **Red:** após adicionar somente o teste de domínio, a compilação falhou pela ausência de `valor()` e `moeda()`. Nenhum caso foi executado nessa etapa.
- **Correção do teste:** a primeira tentativa de green revelou uma asserção inexistente (`hasScale`) na versão instalada do AssertJ. Ela foi substituída pela comparação explícita de `scale()`; somente as alterações próprias do domínio foram desfeitas com patch e o red foi repetido, falhando apenas pelos acessores ausentes. Essa falha acidental não foi usada como evidência do comportamento.
- **Green:** `Transacao` conserva `BigDecimal` e `Currency` em campos privados finais após as validações; acessores permitem leitura, sem setters. O teste focado executou 7 casos sem falhas.
- **Refatoração:** `CriarTransacaoService` passa a compor o resultado com `transacao.valor()` e `transacao.moeda()`. O contrato externo permanece igual; o teste do caso de uso foi ampliado como regressão, sem alegar novo red.
- **Verificação final:** `mvnw.cmd '-Dtest=TransacaoTest,CriarTransacaoServiceTest' test` executou 9 casos verdes; `mvnw.cmd --batch-mode --no-transfer-progress verify` executou 36 testes verdes e gerou o JAR.
- **Aceite:** fixtures `10.00/BRL` e `123.456/USD` preservam valor, escala, moeda e estado `PENDENTE` no domínio e no resultado. Não há arredondamento, conversão de moeda, ID de domínio ou timestamp; `pom.xml`, API e dependências não mudaram.
- **Revisão:** guardar dados numa instância não é persistência. O UUID continua sendo gerado no caso de uso para a resposta. Antes do primeiro adapter PostgreSQL, é necessário definir identidade persistente, representação monetária e contrato do teste de integração. Warning Mockito/Byte Buddy permanece conhecido.

### Evidência TDD — identidade UUID no domínio

- **Entrega:** [PR #20](https://github.com/Joaomagh/credpay/pull/20), com identidade no domínio, integração no caso de uso, testes e documentação.
- **CI e merge:** [CI Linux #33](https://github.com/Joaomagh/credpay/actions/runs/34737914142) verde para `cb37690`; merge em `1d28c94`.
- **Red de preservação:** após adicionar somente o teste parametrizado com dois UUIDs conhecidos, `mvnw.cmd -Dtest=TransacaoTest test` falhou na compilação porque a fábrica ainda não aceitava UUID. Nenhum caso executou nessa etapa.
- **Green de preservação:** a fábrica passou a receber `UUID`, conservado em campo privado final e exposto por `id()`. Os chamadores foram migrados para a nova assinatura; 9 casos de domínio passaram.
- **Red de nulidade:** adicionando somente o teste de ID nulo, o mesmo comando executou 10 casos com uma falha `Expecting code to raise a throwable`; os 9 anteriores passaram.
- **Green de nulidade:** guarda mínima lança `IllegalArgumentException` com mensagem `id deve ser informado` antes das demais validações.
- **Verificação:** `mvnw.cmd '-Dtest=TransacaoTest,CriarTransacaoServiceTest' test` executou 12 casos verdes; `mvnw.cmd --batch-mode --no-transfer-progress verify` executou 39 testes, zero falhas/erros/skips, e gerou o JAR.
- **Revisão do fluxo:** o caso de uso chama `UUID.randomUUID()` uma única vez, passa o ID à fábrica e compõe o resultado com `transacao.id()`. Testes de domínio provam preservação; regressões de aplicação/HTTP continuam verdes, incluindo correspondência entre ID da resposta e `Location`. Não foi adicionado mock estático nem abstração de geração apenas para observar chamadas internas.
- **Ambiente:** uma tentativa de execução combinada parou no wrapper com `Cannot start maven from wrapper`, antes de iniciar testes. A repetição em ambiente autorizado passou sem alterar o wrapper; essa falha operacional não é evidência red. Warning conhecido Mockito/Byte Buddy permanece.
- **Limites:** identidade em memória não fornece persistência, consulta ou idempotência. Sem timestamp, repository, novo endpoint ou dependências; `pom.xml` e API inalterados. A assinatura Java da fábrica mudou e todos os chamadores do monorepo foram atualizados.
- **Próximo:** verificar a baseline de dependências, imagem PostgreSQL e executor confiável antes de implementar o adapter e seu teste de integração.

## 8. Persistência e consistência

A migration de produção abaixo é aplicada pelo Flyway no perfil `persistencia`, com Hibernate em `validate`. A API ainda não usa o repository.

| Serviço | Migration | Mudança | Motivo |
|---|---|---|---|
| transacoes-service | `V1__create_transacoes.sql` | tabela `transacoes`, UUID como PK, valor `numeric`, moeda `varchar(3)` e status `varchar(16)`, todos obrigatórios | primeiro round-trip com identidade e dados monetários preservados |
| transacoes-service | `V2__protect_transaction_amount.sql` | constraint `ck_transacoes_valor_positivo_finito` exige valor maior que zero e exclui `NaN`, `Infinity` e `-Infinity` | proteger a invariante monetária mesmo em gravações que contornem o domínio |
| transacoes-service | `V3__create_transaction_idempotency.sql` | associação única entre chave idempotente e transação | replay e conflito com autoridade de unicidade no PostgreSQL |
| transacoes-service | `V4__create_transaction_outbox.sql` | tabela `outbox_eventos` com envelope JSONB e publicação inicialmente nula | persistir a intenção de publicação antes de introduzir RabbitMQ |

### 8.1 Contrato mínimo da persistência futura

**Entrega documental:** [PR #19](https://github.com/Joaomagh/credpay/pull/19). Revisão de consistência, UTF-8 válido e `git diff --check`; testes não executados neste incremento exclusivamente documental.

**Estado:** identidade, porta/adapter de repository, entidade JPA, migrations V1/V2, primeiro round-trip PostgreSQL e constraint monetária implementados. Constraints de moeda/status, outros cenários negativos e conexão do endpoint permanecem pendentes; não confundir o contrato completo com cobertura já concluída.

#### Identidade e propriedade dos dados

- O `transacoes-service` será o único dono da tabela `transacoes`, em banco exclusivo do serviço. O futuro `processamento-service` não acessará essa tabela diretamente.
- O caso de uso gera um UUID uma única vez, antes de criar a transação. A fábrica de domínio recebe esse UUID, junto de valor e moeda, conservando-o como identidade imutável e não nula.
- O mesmo ID já atravessa domínio, resultado HTTP e `Location`; futuramente também será usado na persistência. O adapter e o banco não gerarão outro ID. O request público continua sem campo de identidade controlável pelo cliente.
- A leitura reconstruirá a transação com o ID e estado armazenados, sem gerar nova identidade ou reiniciar o estado. No primeiro estágio, o único estado suportado continuará sendo `PENDENTE`.
- UUID não é chave de idempotência. Repetir uma requisição ainda não terá garantia de deduplicação; essa capacidade permanece em incremento futuro.

#### Representação monetária e schema planejado

| Campo | Tipo planejado no PostgreSQL | Invariante |
|---|---|---|
| `id` | `uuid` | chave primária; fornecida pela aplicação; imutável |
| `valor` | `numeric` sem precisão/escala declaradas | obrigatório, finito e maior que zero; sem arredondamento no adapter |
| `moeda` | `varchar(3)` | obrigatória; exatamente três letras ASCII maiúsculas |
| `status` | `varchar(16)` | obrigatório; inicialmente somente `PENDENTE` |

`BigDecimal` permanece o tipo Java. Não serão usados `double`, `float` ou o tipo SQL `money`. Uma coluna `numeric(p, s)` pode arredondar a entrada para a escala declarada; por isso não será introduzida uma escala fixa de duas casas que alteraria o comportamento atual. `numeric` sem escala declarada evita essa coerção, mas continua sujeito aos limites de implementação do PostgreSQL. [Referência: tipos numéricos](https://www.postgresql.org/docs/current/datatype-numeric.html).

A primeira integração deverá comprovar valor exato e escala das fixtures `10.00/BRL` e `123.456/USD`. Isso não promete preservar a representação textual original do JSON nem todas as formas de notação científica. Limites de precisão/escala aceitos pela API e tratamento de valores extremos deverão ser definidos e testados antes de conectar o endpoint ao banco; não se converterá falha de armazenamento em arredondamento silencioso.

A V1 protege nulidade e chave primária; a V2 protege valor positivo e finito, excluindo explicitamente `NaN` e infinitos porque apenas verificar `valor > 0` não cobre todos os valores especiais do PostgreSQL. Formato da moeda e estado permitido continuam pendentes. O catálogo ISO 4217 continuará sendo validado na aplicação; três letras no banco não comprovam que uma moeda existe.

Flyway será o dono do schema, usando uma migration versionada em `src/main/resources/db/migration/`. Hibernate apenas validará o mapeamento (`ddl-auto=validate`), sem `create` ou `update`. O mapeamento JPA não poderá impor uma escala diferente da migration. Não haverá tabela exclusiva de teste nem fallback H2.

#### Fronteira do repository e transações

| Elemento planejado | Responsabilidade | O que não expõe |
|---|---|---|
| `application/TransacaoRepository` | porta com `inserir(Transacao)` e `buscarPorId(UUID)`, retornando `Optional<Transacao>` na busca | Spring Data, `EntityManager`, entidades JPA ou detalhes SQL |
| adapter em `infrastructure/persistence` | implementar a porta, mapear domínio/entidade e consultar PostgreSQL | regras de negócio duplicadas ou objetos JPA na API |
| entidade JPA separada | representar as quatro colunas da tabela; persistir status por nome, não ordinal | comportamento HTTP ou geração de novo UUID |
| caso de uso | coordenar a unidade de trabalho de criação | acesso direto ao driver ou ao schema |

`inserir` significa criar um novo registro: colisão de chave deve falhar sem sobrescrever dados existentes. Não será implementado upsert disfarçado de criação. A busca de ID inexistente retorna vazio; indisponibilidade ou erro SQL não podem ser tratados como ausência de registro.

Quando o endpoint for conectado à persistência, a criação ocorrerá numa transação local; `201` somente será enviado após commit bem-sucedido. O teste do adapter será implementado antes dessa conexão. Não haverá evento ou coordenação entre banco e RabbitMQ neste estágio.

#### Primeiro teste de integração e contrato de evidência

O primeiro teste será `TransacaoRepositoryIntegrationTest`, com nome reconhecido pelo Surefire para participar de Maven `test` e `verify`. Usará PostgreSQL real via Testcontainers, migration Flyway de produção e adapter real, sem mocks de banco ou repository. O teste inicial provará escrita e leitura, não apenas que o container iniciou. [Referência: módulo PostgreSQL](https://java.testcontainers.org/modules/databases/postgres/).

| Etapa | Procedimento esperado | Evidência de aceite |
|---|---|---|
| Preparação | iniciar container descartável e aplicar a migration em banco vazio | versão/digest da imagem e migration aplicada identificados |
| Red | executar o teste antes da implementação funcional do adapter/migration | falha atribuível à capacidade ausente, não a Docker indisponível, credencial ou erro acidental do teste |
| Escrita | inserir transação com UUID fixo de fixture e dados válidos; concluir a transação de escrita | commit bem-sucedido |
| Leitura | abrir outra transação e outro contexto de persistência, sem cache compartilhado de entidades | recuperar pelo ID e comparar ID, valor, escala da fixture, moeda e `PENDENTE` |
| Green | repetir teste focado e `verify` | teste de integração realmente executado, zero falhas/erros/skips e regressões anteriores verdes |

O teste não ficará inteiro dentro de uma transação de teste com rollback automático. Escrita e leitura precisam atravessar commits/contextos separados para não gerar um falso positivo vindo do cache JPA. `flush` sem commit não será apresentado como prova de persistência após commit. [Referência: transações em testes Spring](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/tx.html).

Depois do round-trip, ciclos separados deverão provar ID ausente, colisão sem sobrescrita, rollback e constraints com inserções que não passem pelas guardas do domínio. Esses cenários não serão declarados concluídos pelo primeiro teste.

#### Ambiente, sequência e limites

- Antes de adicionar dependências, verificar a compatibilidade das versões gerenciadas pelo Spring Boot para JPA, driver PostgreSQL, Flyway e Testcontainers. Fixar a imagem PostgreSQL com versão explícita e digest verificado; não usar `latest` nem copiar automaticamente a versão ilustrativa do guia.
- O teste exigirá Docker disponível localmente e no CI; ausência de Docker será falha de ambiente, não teste aprovado ou ignorado. Credenciais serão fictícias, com portas dinâmicas, dados isolados e descarte automático; reuso de containers não será requisito.
- O executor de Testcontainers precisa acessar o daemon Docker e, inicialmente, obter imagens. Isso é incompatível com o `sandbox-core` diagnóstico sem Docker socket/rede; este contrato não afirma que os testes rodarão dentro do AI-Jail. Um executor de desenvolvimento confiável deverá ser explicitado antes da execução.
- A ordem será: identidade no domínio em TDD; baseline de dependências/imagem e ambiente; round-trip do adapter em TDD; testes de constraints e limites monetários; somente depois conectar o endpoint à persistência.
- No incremento documental original, toda implementação estava fora do escopo. O adapter foi posteriormente comprovado na seção 8.4; consulta HTTP, idempotência, atualização de status, timestamp/auditoria, outbox, RabbitMQ, segundo serviço, Docker Compose, Kubernetes e CD continuam pendentes.

### 8.2 Baseline de dependências e executor de persistência

**Entrega documental:** [PR #21](https://github.com/Joaomagh/credpay/pull/21).

Baseline definida em 2026-09-13 e materializada no primeiro adapter. O driver passou de `test` para `runtime`; JPA e Flyway foram adicionados nos escopos abaixo. Módulos Testcontainers continuam restritos a testes.

| Dependência | Escopo Maven | Versão gerenciada | Motivo |
|---|---|---|---|
| `org.springframework.boot:spring-boot-starter-data-jpa` | compile | 3.5.16 | JPA, transações locais e Hibernate para o adapter |
| `org.postgresql:postgresql` | runtime | 42.7.11 | driver JDBC do PostgreSQL |
| `org.flywaydb:flyway-core` | compile | 11.7.2 | aplicar migrations versionadas |
| `org.flywaydb:flyway-database-postgresql` | runtime | 11.7.2 | suporte específico do Flyway ao PostgreSQL |
| `org.testcontainers:junit-jupiter` | test | 1.21.4 | ciclo de vida dos containers nos testes JUnit |
| `org.testcontainers:postgresql` | test | 1.21.4 | PostgreSQL descartável com URL e portas dinâmicas |

Não sobrescrever versões nem importar outro BOM: o parent Spring Boot existente gerencia esse conjunto. `mvnw.cmd -o help:effective-pom`, sem downloads, confirmou Flyway 11.7.2, PostgreSQL JDBC 42.7.11, Testcontainers 1.21.4 e Hibernate 6.6.53.Final. A [tabela oficial do Spring Boot 3.5](https://docs.spring.io/spring-boot/3.5/appendix/dependency-versions/coordinates.html) foi conferida; o teste do adapter comprovou a execução desse conjunto.

O teste usará `@DynamicPropertySource` para configurar a conexão, conforme o guia local; `spring-boot-testcontainers` não é necessário nessa opção. H2, RabbitMQ, bibliotecas de migração alternativas, Docker Compose e dependências de observabilidade permanecem fora. Ao adicionar JPA/Flyway, preservar explicitamente a inicialização e as regressões HTTP; não desabilitar globalmente as auto-configurações para ocultar falhas do teste de integração.

#### Imagem PostgreSQL

- Escolha: `postgres:17.11-bookworm`, linha estável suportada com patch atual consultado, baseada em Debian. Não usar a tag ilustrativa `16-alpine` do guia nem `latest`.
- Digest do índice publicado: `sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0`.
- Digest da imagem Linux amd64: `sha256:7bade6d532592ca8ce7ee32def7399dad2607c4ea5583839fc4352a095a11ea6`.
- Referência executada no teste: `postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0`, correspondente à tag `17.11-bookworm`. A forma combinada `tag@digest` foi rejeitada pela verificação de nome do Testcontainers 1.21.4; usar somente o digest preserva a fixação sem contornar a verificação de compatibilidade.
- Evidência: metadados consultados na [API pública do Docker Hub](https://hub.docker.com/v2/repositories/library/postgres/tags/17.11-bookworm), tag confirmada no [catálogo de imagens oficiais](https://github.com/docker-library/official-images/blob/master/library/postgres) e versão conferida na [política de versões PostgreSQL](https://www.postgresql.org/support/versioning/). Nenhuma imagem foi baixada ou executada neste incremento. Fixar digest não elimina vulnerabilidades: atualizações exigem revisão e repetição dos testes.

#### Executor e diagnóstico inicial de ambiente

O executor planejado é Maven no host de desenvolvimento confiável com Docker Desktop em modo Linux; no CI, Maven no runner Linux hospedado do GitHub Actions. Esse ambiente não é o `sandbox-core`: Testcontainers precisa do daemon Docker e de acesso aos registros para obter imagens. Usar apenas fixtures fictícias e containers descartáveis do projeto, sem mounts de dados pessoais. Não configurar daemon remoto sem autenticação nem aplicar limpeza global de containers/volumes.

Na verificação inicial de 2026-09-13, `docker version` identificou CLI 28.4.0, mas não conseguiu acessar o servidor. A repetição autorizada falhou porque o pipe `dockerDesktopLinuxEngine` não foi encontrado. Naquele momento, nenhum container foi criado. Esse impedimento foi resolvido posteriormente, conforme seção 8.3. Os [requisitos de runtime do Testcontainers](https://java.testcontainers.org/supported_docker_environment/) exigem um runtime compatível acessível.

Antes do código de persistência, disponibilizar o engine Linux e confirmar versão/API do servidor; depois validar obtenção da imagem fixada e compatibilidade real com Testcontainers 1.21.4, incluindo descarte. O CI também deverá executar o teste, não ignorá-lo por ausência de Docker. Não atualizar dependências ou forçar versão da API Docker para esconder incompatibilidade sem diagnóstico.

**Validação deste incremento:** revisão documental, consulta offline ao POM efetivo, consulta dos metadados da imagem e diagnóstico Docker. Sem testes de aplicação novos ou reexecutados; os 39 testes verdes e CI do PR #20 são evidências anteriores. Persistência continua não implementada.

### 8.3 Executor PostgreSQL comprovado

- **Entrega:** [PR #22](https://github.com/Joaomagh/credpay/pull/22).
- **CI e merge:** [CI Linux #36](https://github.com/Joaomagh/credpay/actions/runs/34773333605) verde para `6e9c22d`; merge em `60cdd14`.
- **Recuperação local:** `docker desktop start --timeout 45` expirou. O executável instalado do Docker Desktop foi iniciado em segundo plano; após a inicialização, engine Linux 28.4.0/API 1.51 ficou acessível em Docker Desktop 4.46.0, WSL 2, amd64. Nenhuma configuração do daemon foi alterada.
- **Implementação:** `PostgresRuntimeTest` usa PostgreSQL real via Testcontainers 1.21.4 e driver 42.7.11, com imagem fixada pelo digest da seção 8.2, porta dinâmica e credenciais fictícias. Consulta `server_version_num = 170011` e envia/recebe `BigDecimal` via JDBC; fixtures `10.00` e `123.456` preservam magnitude e escala.
- **Escopo:** teste operacional de compatibilidade, não teste de persistência de transação. Não cria tabela, entidade, migration ou repository e não usa contexto Spring. Como configuração/verificação operacional, não se declara um ciclo red/green de negócio.
- **Falha observada:** a primeira execução falhou na inicialização da fixture por incompatibilidade do nome `tag@digest`; a correção para `postgres@digest` passou mantendo o mesmo artefato. Não houve falha de regra de domínio nem downgrade de dependências.
- **Verificação:** `mvnw.cmd --batch-mode --no-transfer-progress -Dtest=PostgresRuntimeTest test`: 2 casos verdes; `mvnw.cmd --batch-mode --no-transfer-progress verify`: 41 testes, zero falhas/erros/skips e JAR gerado. As 39 regressões anteriores permanecem verdes.
- **Descarte:** consultas `docker ps -a --filter id=...` pelos IDs específicos do PostgreSQL e Ryuk do teste focado retornaram vazio após o encerramento da JVM. Nenhuma limpeza global foi executada. Imagens permanecem em cache; Docker Desktop continua iniciado.
- **Limite de confiança:** Testcontainers usou o daemon local e o auxiliar `testcontainers/ryuk:0.12.0` para limpeza. Esse auxiliar é selecionado pela biblioteca e não foi fixado por digest neste incremento. Isso não constitui execução dentro do AI-Jail nem comprova seus controles.
- **Dependências naquele incremento:** somente driver e dois módulos Testcontainers no escopo `test`, sem PostgreSQL no JAR da aplicação. O escopo do driver e a inclusão de JPA/Flyway evoluíram no incremento da seção 8.4. Não há skip automático quando Docker falta; `test`, `package` e `verify` completos exigem engine acessível e imagens disponíveis.
- **Revisão periódica:** README corrigido quanto ao UUID de domínio e aos pré-requisitos dos testes. Warning conhecido Mockito/Byte Buddy permanece. Próximo incremento: primeiro round-trip do repository, com migration de produção e commits/contextos separados conforme seção 8.1.

### 8.4 Primeiro round-trip do repository

- **Entrega:** [PR #23](https://github.com/Joaomagh/credpay/pull/23).
- **Red:** adicionados apenas o teste de integração e as dependências de sua baseline; teste focado falhou pela ausência de `TransacaoRepository` e `TransacaoJpaRepository`. O resultado foi tipado como `Optional<Transacao>` e o red repetido para eliminar mensagens em cascata da inferência; restaram apenas os tipos ausentes. Nenhum teste executou nessa etapa.
- **Green:** porta em `application`, adapter JPA em `infrastructure/persistence`, entidade separada e migration V1. O adapter usa `persist`, não `merge`, para inserção; `find` e `Optional` para busca. O chamador coordena a transação; o adapter não faz commit independente.
- **Reconstrução:** o ID armazenado é reutilizado; o mapeamento de estado usa `switch` exaustivo, atualmente com apenas `PENDENTE`. Novos estados exigirão ampliar explicitamente o mapeamento, sem fallback que os reinicie silenciosamente.
- **Teste real:** `TransacaoRepositoryIntegrationTest`, com `@DataJpaTest`, PostgreSQL fixado por digest, Flyway de produção e Hibernate `validate`; sem H2 ou mocks. O teste desativa a transação externa de teste e usa duas chamadas de `TransactionTemplate`: escrita concluída antes da leitura, com contextos distintos e caches de segundo nível/consulta desabilitados. Logs mostram INSERT e SELECT reais.
- **Aceite:** duas fixtures de UUID, `10.00/BRL` e `123.456/USD`, preservam identidade, magnitude, escala, moeda e estado após commit. O `numeric` sem escala fixa não arredondou essas fixtures.
- **Verificação:** `mvnw.cmd --batch-mode --no-transfer-progress -Dtest=TransacaoRepositoryIntegrationTest test`: 2 casos verdes; `mvnw.cmd --batch-mode --no-transfer-progress verify`: 43 testes, zero falhas/erros/skips e JAR gerado. Contexto padrão e regressões HTTP continuam verdes sem DataSource.
- **Ativação deliberada:** `application.yml` exclui a auto-configuração de DataSource somente quando o perfil `persistencia` não está ativo; no perfil, Flyway/JPA funcionam normalmente, `ddl-auto=validate` e `open-in-view=false`. O adapter é bean apenas nesse perfil. Não há exclusão global que mascare o teste de banco. Ativar o perfil exige configurar conexão; não instala nem inicia PostgreSQL automaticamente fora dos testes.
- **Limites:** V1 contém PK e NOT NULL, mas ainda não tem CHECKs monetários, de formato de moeda ou de estados. O domínio continua protegendo sua fábrica; gravações SQL diretas ainda não têm todas as guardas planejadas. ID inexistente, colisão sem sobrescrita e rollback exigem cenários próprios. O endpoint não chama o repository, mesmo com o perfil ativo; não há GET, eventos ou timestamp.
- **Revisão:** a configuração sem banco é transitória até conectar o caso de uso. As dependências de produção foram adicionadas por necessidade do adapter, sem novos BOMs ou versões sobrescritas. Warning conhecido Mockito/Byte Buddy permanece. Próximo comportamento: constraint monetária no PostgreSQL, com teste que contorne as validações do domínio.
- **Incidente após os testes:** o Docker Desktop encerrou com erro ao inicializar o gerenciador de inferência porque não conseguiu remover/acessar `dockerInference`; depois disso, CLI e engine ficaram indisponíveis. O erro ocorreu após o teste focado e o `verify` verdes, sem invalidar seus resultados já concluídos, mas bloqueia novas execuções locais com Testcontainers até reiniciar/diagnosticar o Docker. Nenhum arquivo interno do Docker foi removido e nenhuma limpeza ou reset foi aplicado.

### 8.5 Constraint monetária no PostgreSQL

- **Entrega:** [PR #24](https://github.com/Joaomagh/credpay/pull/24).
- **CI e merge:** [CI Linux #42](https://github.com/Joaomagh/credpay/actions/runs/34790415311) verde para `fcd64c5`; merge em `203cc3e`.
- **Red:** após adicionar somente o teste parametrizado, PostgreSQL 17.11 com V1 aceitou por SQL direto `0`, `-0.01`, `NaN`, `Infinity` e `-Infinity`. O teste focado executou 7 casos: os 2 round-trips válidos passaram e os 5 casos novos falharam com `Expecting code to raise a throwable`.
- **Green:** a migration V2 adiciona `ck_transacoes_valor_positivo_finito`, exigindo `valor > 0` e excluindo explicitamente os três valores especiais do tipo `numeric`. Nenhuma validação Java, dependência ou contrato HTTP mudou.
- **Evidência do controle:** o teste usa `JdbcTemplate` e `INSERT` direto, sem fábrica de domínio ou adapter JPA, e exige `DataIntegrityViolationException` com o nome da constraint na stack trace. Assim, a falha é atribuída ao controle do banco, não a outra validação da aplicação.
- **Verificação:** teste focado com 7 casos verdes após V1/V2; `mvnw.cmd --batch-mode --no-transfer-progress verify` com 48 testes, zero falhas/erros/skips e JAR gerado. Após fortalecer a asserção da causa, os 7 casos focados foram repetidos e continuaram verdes.
- **Ambiente:** o Docker Desktop voltou a responder sem limpeza ou reset; Testcontainers conectou ao engine 28.4.0/API 1.51 e criou containers descartáveis. O warning conhecido de autoanexação Mockito/Byte Buddy permanece.
- **Limites:** a constraint não define precisão/escala máximas, formato da moeda ou status permitido. Colisão de UUID, busca ausente, rollback e conexão do endpoint continuam sem cobertura própria.
- **Próximo:** provar que uma segunda inserção com o mesmo UUID falha sem sobrescrever o registro original.

### 8.6 Colisão de UUID sem sobrescrita

- **Entrega:** [PR #25](https://github.com/Joaomagh/credpay/pull/25).
- **CI e merge:** [CI Linux #45](https://github.com/Joaomagh/credpay/actions/runs/34790749602) verde para `ff596d4`; merge em `38af046`.
- **Teste de caracterização:** a primeira inserção usa `10.00/BRL`; a segunda usa o mesmo UUID com `20.00/USD`. A segunda transação falha com `DataIntegrityViolationException`, SQLState `23505` e referência a `transacoes_pkey`.
- **Integridade preservada:** depois da transação que falhou, uma nova leitura recupera `10.00/BRL`, escala 2 e `PENDENTE`. Não existe upsert nem sobrescrita silenciosa.
- **TDD honesto:** o teste nasceu verde porque a PK da V1 e o uso de `EntityManager.persist` já forneciam o comportamento. Nenhum red foi fabricado e nenhum código de produção foi alterado; o valor do incremento é tornar a garantia executável contra regressões.
- **Verificação:** teste focado com 8 casos verdes; `mvnw.cmd --batch-mode --no-transfer-progress verify` com 49 testes, zero falhas/erros/skips e JAR gerado. PostgreSQL 17.11/Flyway V1-V2/Testcontainers foram usados de verdade.
- **Limites:** a exceção ainda não é traduzida para um erro de aplicação porque o endpoint não usa o repository. Busca ausente e rollback ainda exigem cobertura própria.
- **Próximo:** provar que buscar um UUID inexistente retorna vazio sem mascarar erros de infraestrutura.

### 8.7 Busca por UUID inexistente

- **Entrega:** [PR #26](https://github.com/Joaomagh/credpay/pull/26).
- **CI e merge:** [CI Linux #48](https://github.com/Joaomagh/credpay/actions/runs/34791060231) verde para `fe19784`; merge em `28c4498`.
- **Teste de caracterização:** uma busca por UUID fixo que não foi inserido executa SELECT real em outra transação e retorna `Optional.empty()`.
- **Falhas não mascaradas:** `TransacaoJpaRepository` converte somente o `null` legítimo retornado por `EntityManager.find`. O adapter não captura exceções; indisponibilidade, timeout ou falha SQL continuam propagando e não são apresentados como ausência.
- **TDD honesto:** o teste nasceu verde porque `Optional.ofNullable(entityManager.find(...))` já implementava o contrato. Nenhuma falha artificial foi criada e nenhum código de produção mudou.
- **Verificação:** teste focado com 9 casos verdes; `mvnw.cmd --batch-mode --no-transfer-progress verify` com 50 testes, zero falhas/erros/skips e JAR gerado. PostgreSQL 17.11, Flyway V1-V2 e Testcontainers foram executados.
- **Limites:** não há consulta HTTP nem teste deliberado de indisponibilidade do banco. O próximo cenário isolará rollback antes da conexão do endpoint.
- **Próximo:** provar que uma inserção em transação revertida não fica visível em leitura posterior.

### 8.8 Rollback da inserção

- **Entrega:** [PR #27](https://github.com/Joaomagh/credpay/pull/27).
- **CI e merge:** [CI Linux #51](https://github.com/Joaomagh/credpay/actions/runs/34793061473) verde para `0e61edf`; merge em `1d396a5`.
- **Teste de caracterização:** o repository recebe uma transação válida dentro de `TransactionTemplate`; `EntityManager.flush()` força o INSERT real antes de `setRollbackOnly()`.
- **Integridade preservada:** depois do rollback, uma nova transação executa SELECT pelo mesmo UUID e retorna vazio. O adapter participa da unidade de trabalho coordenada e não confirma a escrita independentemente.
- **TDD honesto:** o teste nasceu verde porque `EntityManager.persist` já participa da transação JPA. Nenhum red foi fabricado e nenhum código de produção foi alterado.
- **Verificação:** teste focado com 10 casos verdes e log de INSERT/SELECT; `mvnw.cmd --batch-mode --no-transfer-progress verify` com 51 testes, zero falhas/erros/skips e JAR gerado.
- **Limites:** o teste não cobre falha no momento do commit nem a resposta HTTP correspondente. A aplicação ainda possui um modo padrão transitório sem DataSource e o endpoint não persiste.
- **Próximo:** definir a baseline de ativação do PostgreSQL e da fronteira transacional antes de conectar o caso de uso ao repository.

### 8.9 Baseline de ativação da persistência na criação

**Status:** aprovada para o próximo incremento de implementação.

**Entrega:** [PR #28](https://github.com/Joaomagh/credpay/pull/28).

#### Execução real

- PostgreSQL será obrigatório para iniciar e executar o `transacoes-service`; não haverá repository volátil, fallback em memória ou criação não persistida quando a aplicação real estiver ativa.
- O adapter JPA deixará de depender do perfil `persistencia`. A exclusão condicional de `DataSourceAutoConfiguration` será removida.
- `spring.jpa.hibernate.ddl-auto=validate` e `spring.jpa.open-in-view=false` valerão na configuração normal. Flyway continuará sendo o único responsável por criar/evoluir o schema.
- A conexão será fornecida pelas propriedades padrão do Spring, normalmente `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` e `SPRING_DATASOURCE_PASSWORD`. Nenhuma credencial ou URL operacional terá valor padrão versionado.
- Ausência ou indisponibilidade do banco fará a aplicação falhar de modo observável; não será convertida em execução degradada que aceite dados voláteis.

#### Fronteira transacional

- `CriarTransacaoService` receberá `TransacaoRepository` por injeção de construtor, criará a entidade de domínio e chamará `inserir` antes de produzir o resultado.
- O caso de uso será a fronteira da transação local com `@Transactional`; o adapter continuará sem iniciar ou confirmar transações próprias.
- Exceções de persistência continuarão propagando. O controller só poderá construir/enviar `201 Created` depois que a chamada transacional retornar, portanto depois do commit bem-sucedido.
- Este incremento não adicionará outbox ou evento: a atomicidade entre PostgreSQL e RabbitMQ será tratada quando a publicação confiável entrar.

#### Estratégia de testes

| Teste | Papel após a mudança | Infraestrutura |
|---|---|---|
| `CriarTransacaoServiceTest` | provar que o domínio criado é entregue ao repository e que o resultado preserva os mesmos dados | mock somente da porta `TransacaoRepository` |
| `TransacaoControllerTest` | manter o contrato MVC isolado do caso de uso | mock da porta `CriarTransacao` |
| `TransacaoHttpTest` | provar HTTP → controller → caso de uso transacional → adapter → PostgreSQL e leitura após o `201` | contexto Spring completo e PostgreSQL/Testcontainers |
| `TransacaoRepositoryIntegrationTest` | manter migrations, constraints e semântica isolada do adapter | slice JPA e PostgreSQL/Testcontainers |

`TransacoesServiceApplicationTest`, que hoje apenas sobe o contexto sem banco, será removido quando `TransacaoHttpTest` comprovar a inicialização do contexto real com PostgreSQL; manter ambos não acrescentaria um controle diferente. Casos unitários e MVC podem continuar sem banco porque substituem explicitamente a fronteira externa, nunca porque a aplicação de produção tenha fallback oculto.

#### Limites do próximo incremento

Sem Docker Compose, consulta HTTP, idempotência, tradução específica de indisponibilidade/constraint, RabbitMQ, outbox, evento ou dependência nova. A configuração de execução local com PostgreSQL será preparada em incremento próprio depois que a conexão do caso de uso estiver comprovada por Testcontainers.

### 8.10 Criação HTTP persistente e transacional

- **Entrega:** [PR #29](https://github.com/Joaomagh/credpay/pull/29).
- **CI e merge:** [CI Linux #54](https://github.com/Joaomagh/credpay/actions/runs/34793659500) verde para `299f3ca`; merge em `6994aef`.
- **Red unitário:** após alterar somente `CriarTransacaoServiceTest`, a compilação falhou porque o serviço ainda aceitava apenas o construtor sem argumentos. O teste novo exigia a porta `TransacaoRepository` e a entrega da transação criada para `inserir`.
- **Green unitário:** o serviço passou a receber o repository por construtor, chamar `inserir` antes de compor o resultado e executar `executar` com `@Transactional`. Os 2 casos unitários passaram e verificaram UUID, valor/escala, moeda e `PENDENTE` na entidade entregue à porta.
- **Red HTTP:** com somente o teste HTTP preparado para PostgreSQL real, o container iniciou, mas o contexto padrão produziu 25 erros por uma causa única: não havia bean `TransacaoRepository`, pois o adapter dependia do perfil `persistencia` e o DataSource estava excluído por padrão.
- **Green HTTP:** o perfil/fallback sem banco foi removido, o adapter JPA passou a ser bean normal e a configuração sempre usa Flyway, Hibernate `validate` e `open-in-view=false`. `TransacaoHttpTest` inicia PostgreSQL 17.11, executa o fluxo completo e relê cada transação válida em nova transação após o `201`.
- **Proxy transacional:** `CriarTransacaoService` deixou de ser `final` porque a configuração padrão do Spring usa proxy de classe para `@Transactional`; a classe continua package-private e sem extensão de domínio.
- **Verificação:** `CriarTransacaoServiceTest` com 2 casos verdes; `TransacaoHttpTest` com 25 casos verdes; `mvnw.cmd --batch-mode --no-transfer-progress verify` com 50 testes, zero falhas/erros/skips e JAR gerado. O total anterior de 51 caiu pela remoção deliberada de `TransacoesServiceApplicationTest`; a inicialização do contexto real agora é coberta pelo teste HTTP com PostgreSQL.
- **Configuração:** execução real exige `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` e `SPRING_DATASOURCE_PASSWORD`. Sem banco válido, o serviço falha ao iniciar em vez de aceitar transações voláteis. Nenhum segredo foi adicionado.
- **Limites:** sem consulta HTTP, Docker Compose, idempotência, tratamento específico de indisponibilidade, RabbitMQ, outbox, evento ou dependência nova.
- **Próximo:** definir o contrato mínimo de `GET /transacoes/{id}` antes da implementação.

### 8.11 Consulta HTTP por UUID válido

- **Entrega:** [PR #31](https://github.com/Joaomagh/credpay/pull/31).
- **CI e merge:** [CI Linux #57](https://github.com/Joaomagh/credpay/actions/runs/34801824795) verde para `530f29d`; merge em `e4a81fb`.
- **Red de aplicação:** após adicionar somente `BuscarTransacaoServiceTest`, a compilação falhou pela ausência de `BuscarTransacaoService` e `TransacaoNaoEncontradaException`.
- **Green de aplicação:** a porta `BuscarTransacao` e o serviço mínimo passaram 2 testes, consultando o repository em `@Transactional(readOnly = true)`, preservando os dados encontrados e lançando erro explícito quando ausentes.
- **Red MVC:** os dois cenários GET falharam com o handler de recurso estático: o existente recebeu `404` em vez de `200` e o ausente não recebeu `application/problem+json`.
- **Green MVC:** o controller passou a delegar a consulta e o advice traduz `TransacaoNaoEncontradaException` para `404` com título e detalhe estáveis. Os 3 testes MVC, incluindo a criação preexistente, ficaram verdes.
- **Integração:** `TransacaoHttpTest` cria uma transação pelo POST e a consulta pelo UUID contra PostgreSQL real; outro cenário comprova `404 Problem Details` para UUID válido não persistido. Os 27 casos HTTP ficaram verdes.
- **Verificação:** `mvnw.cmd --batch-mode --no-transfer-progress verify` executou 56 testes, zero falhas/erros/skips e gerou o JAR. PostgreSQL 17.11, Flyway V1-V2 e Testcontainers foram executados.
- **Limites:** sem tratamento contratual de UUID malformado, listagem, paginação, filtros, cache, lock, autenticação, eventos ou dependência nova.
- **Próximo:** definir o contrato HTTP mínimo para UUID malformado antes de implementá-lo.

### 8.12 Baseline de erro para UUID malformado

- **Entrega:** [PR #32](https://github.com/Joaomagh/credpay/pull/32).
- **Implementação:** [PR #33](https://github.com/Joaomagh/credpay/pull/33).
- **CI e merge:** [CI Linux #60](https://github.com/Joaomagh/credpay/actions/runs/34803286898) verde para `1f1498d`; merge em `0ea9620`.
- **Decisão:** distinguir sintaxe inválida (`400`) de ausência de recurso (`404`) e manter uma mensagem pública estável, sem detalhes do conversor Java.
- **Fronteira:** a conversão do path ocorre antes da porta `BuscarTransacao`; o teste MVC deverá provar ausência de interação com o caso de uso.
- **Red MVC:** o Spring converteu o path antes do controller, mas `MethodArgumentTypeMismatchException` foi capturada pelo handler genérico de `IllegalArgumentException`; o teste recebeu `422` e a mensagem interna `Invalid UUID string: nao-e-uuid` em vez do contrato seguro.
- **Green MVC:** um handler específico passou a retornar `400`, título e detalhe estáveis. Os 4 testes MVC ficaram verdes e `verifyNoInteractions` comprovou que `BuscarTransacao` não foi chamado.
- **Integração:** o contexto Spring completo com PostgreSQL/Testcontainers retornou o mesmo Problem Details; `TransacaoHttpTest` executou 28 casos verdes.
- **Verificação:** `mvnw.cmd --batch-mode --no-transfer-progress verify` executou 58 testes, zero falhas/erros/skips e gerou o JAR.
- **Limites:** não cobre rota sem ID, parâmetros extras, normalização textual, listagem ou outro tipo de path variable.
- **Próximo:** definir o contrato mínimo de idempotência da criação antes da implementação.

### 8.13 Baseline de idempotência da criação

- **Entrega:** [PR #34](https://github.com/Joaomagh/credpay/pull/34).
- **Chave:** header obrigatório `Idempotency-Key` em formato UUID, distinto do UUID da transação.
- **Repetição:** mesmo payload repete a resposta original; payload diferente produz `409` e não altera a primeira transação.
- **Equivalência:** valor é comparado numericamente e moeda por código; diferenças de JSON ou escala textual não criam outra operação.
- **Concorrência:** unicidade e atomicidade pertencem ao PostgreSQL; check-then-insert sem controle no banco é insuficiente.
- **Retenção:** sem expiração na v1; crescimento e política de limpeza são riscos residuais documentados.
- **Implementação incremental:** primeiro migration/adapter e testes de unicidade/busca; depois semântica do caso de uso; por fim header e contratos HTTP.
- **Limites:** nenhuma migration, API ou regra foi alterada neste incremento documental.
- **Próximo:** implementar a persistência mínima da chave idempotente sem mudar o endpoint ainda.

### 8.14 Persistência da chave idempotente

- **Entrega:** [PR #35](https://github.com/Joaomagh/credpay/pull/35).
- **Desenho:** V3 cria `idempotencias_transacao`, com chave UUID como PK, `transacao_id` obrigatório, único e referenciado por FK. A tabela de associação permite introduzir a infraestrutura sem fingir que o POST atual já é idempotente.
- **Porta:** `TransacaoRepository` recebe operações adicionais de inserção associada e busca por chave; as operações antigas permanecem enquanto o caso de uso ainda não foi migrado.
- **Adapter:** `IdempotenciaTransacaoEntity` associa chave e entidade de transação; `CascadeType.PERSIST` grava ambas na mesma transação local.
- **Red:** o primeiro teste falhou na compilação somente porque a porta ainda não aceitava `inserir(chave, transacao)` nem `buscarPorChaveIdempotencia(chave)`.
- **Green focado:** após V3, entidade e adapter, o teste real aplicou três migrations e executou 11 casos verdes, incluindo INSERT da transação, INSERT da associação e SELECT posterior em outra transação.
- **Controles adicionais:** testes de caracterização exigem que chave duplicada falhe pela PK sem substituir a associação original e que chave nula seja rejeitada. Eles foram adicionados depois do primeiro green.
- **Incidente de ambiente:** na verificação final local, Docker Desktop estava sem o pipe do engine Linux. O `verify` executou 18 testes sem Docker com sucesso, mas três classes Testcontainers abortaram antes dos cenários. Uma tentativa segura de iniciar o Desktop não restaurou o engine; nenhum reset, prune ou remoção foi feito.
- **CI:** [CI Linux #63](https://github.com/Joaomagh/credpay/actions/runs/35047832084) verde para `adc567b`; a suíte executou 61 testes, incluindo os três cenários novos de idempotência, sem falhas/erros/skips, e gerou o JAR.
- **Limites:** endpoint e caso de uso ainda ignoram idempotência; não há replay, conflito HTTP ou teste concorrente neste incremento.
- **Próximo:** implementar a semântica idempotente no caso de uso, ainda sem alterar o contrato HTTP.

### 8.15 Semântica idempotente no caso de uso

- **Entrega:** [PR #36](https://github.com/Joaomagh/credpay/pull/36).
- **CI e merge:** [CI Linux #67](https://github.com/Joaomagh/credpay/actions/runs/35049898921) verde para `45f81e3`; merge em `477c0e5`, com 67 testes na suíte.
- **Red:** seis novos casos não compilaram porque `CriarTransacaoService` ainda não aceitava chave e `ConflitoIdempotenciaException` não existia.
- **Primeira criação:** uma chave ausente no repository gera transação validada pelo domínio e usa `inserir(chave, transacao)`.
- **Replay:** valor numericamente equivalente por `BigDecimal.compareTo` e mesma moeda devolvem a transação original, preservando UUID e escala, sem nova inserção.
- **Conflito:** valor ou moeda diferentes lançam `ConflitoIdempotenciaException` com a mensagem pública aprovada e não gravam outra transação.
- **Validação:** a candidata é criada antes da consulta, portanto replay não contorna as validações de domínio existentes.
- **Green:** `CriarTransacaoServiceTest` executou 8 casos verdes; a suíte unitária/MVC afetada executou 24 testes sem falhas.
- **Limite conhecido:** duas primeiras requisições concorrentes ainda podem observar ausência antes de uma vencer a PK; convergência após essa disputa será tratada antes de conectar HTTP.
- **Próximo:** provar e implementar a convergência concorrente para a mesma chave.

### 8.16 Convergência concorrente da idempotência

- **Red:** o teste unitário exigiu que o repository bloqueasse a chave antes da consulta e falhou na compilação porque o controle ainda não existia.
- **Controle:** `TransacaoJpaRepository` usa `pg_advisory_xact_lock` derivado da chave; como o lock é transacional, PostgreSQL o libera automaticamente no commit ou rollback.
- **Ordem:** `CriarTransacaoService` valida a candidata, adquire o lock e só então consulta/insere. Requisições da mesma chave são serializadas; chaves diferentes não compartilham deliberadamente o mesmo lock, salvo colisão de hash conservadora.
- **Teste real:** duas threads iniciam juntas, cada uma em sua transação Spring, e devem retornar o mesmo UUID; o banco deve conter uma única associação para a chave.
- **Validação local:** 8 testes unitários verdes e toda a suíte compilada. Docker local permanece indisponível, portanto o cenário concorrente PostgreSQL é barreira obrigatória do CI antes do merge.
- **Aprendizado do CI:** a primeira execução ([CI Linux #69](https://github.com/Joaomagh/credpay/actions/runs/35053308216)) revelou que o retorno `void` de `pg_advisory_xact_lock` não pode ser extraído como `Long` pelo Hibernate. A consulta foi ajustada para adquirir o lock no `FROM` e retornar o literal `1`.
- **Evidência:** [PR #37](https://github.com/Joaomagh/credpay/pull/37); [CI Linux #70](https://github.com/Joaomagh/credpay/actions/runs/35053556037) verde para `e53803c`, incluindo o cenário concorrente com PostgreSQL real.
- **Limites:** lock específico de PostgreSQL; não há timeout próprio, métrica de espera nem contrato HTTP neste incremento.
- **Próximo:** conectar `Idempotency-Key` ao endpoint e traduzir ausência, formato inválido e conflito.

### 8.17 Idempotência no contrato HTTP

- **Red:** `TransacaoControllerTest` passou a exigir o header e seus erros; 7 testes foram executados e 4 falharam porque o controller ignorava a chave e chamava o caminho não idempotente, produzindo resultado nulo.
- **Green MVC:** o controller valida presença e formato UUID canônico, chama exclusivamente o caso de uso idempotente e o advice traduz chave inválida em `400` e conflito em `409`; os 7 testes MVC passaram.
- **Refactor:** o método de criação sem chave foi removido da porta e do serviço, evitando um caminho interno que contornasse a idempotência obrigatória.
- **Teste real:** `TransacaoHttpTest` envia chave em toda criação e cobre replay com corpo e `Location` originais, além do conflito com payload diferente.
- **Validação local:** 13 testes focados de controller e aplicação verdes; `test-compile` verde.
- **Evidência:** [PR #38](https://github.com/Joaomagh/credpay/pull/38); [CI Linux #73](https://github.com/Joaomagh/credpay/actions/runs/35054238285) verde para `5ecda07`, com 71 testes sem falhas, erros ou skips e JAR gerado.
- **Limites:** não há expiração, escopo por cliente, autenticação, evento, outbox ou RabbitMQ.
- **Próximo:** definir o contrato versionado mínimo de `TransacaoCriada` e a estratégia de outbox antes de implementar mensageria.

### 8.18 Baseline da outbox transacional

**Estado:** migration V4, modelo, porta e adapter JDBC implementados; escrita atômica, leitura ordenada de pendentes e marcação de publicação comprovadas.

A primeira criação persistirá a transação, a associação idempotente e um único registro de outbox na mesma transação PostgreSQL. Replay equivalente apenas devolve o recurso existente; conflito não grava transação nem evento. Qualquer falha na gravação da outbox reverte toda a criação.

| Coluna planejada | Tipo PostgreSQL | Responsabilidade |
|---|---|---|
| `event_id` | `uuid` | chave primária e identidade usada na deduplicação |
| `aggregate_id` | `uuid` | ID da transação e chave de negócio do evento |
| `event_type` | `varchar(100)` | literal `TransacaoCriada` |
| `event_version` | `integer` | versão positiva do contrato, inicialmente `1` |
| `payload` | `jsonb` | envelope completo já serializado |
| `occurred_at` | `timestamptz` | instante UTC do fato |
| `published_at` | `timestamptz` nulo | ausente enquanto pendente; preenchido somente após publicação confirmada |

O caso de uso criará o evento somente no caminho de primeira criação e o entregará a uma porta de outbox separada. O serviço continuará com uma única transação local Spring; não haverá transação distribuída entre PostgreSQL e RabbitMQ.

O publicador futuro lerá registros com `published_at` nulo, publicará e depois marcará o instante. Uma queda depois da publicação e antes da marcação causa reentrega: a garantia será pelo menos uma vez, não exatamente uma vez. Estratégia de claim/lease entre réplicas, batch, backoff, número de tentativas, retenção e limpeza serão decididos com os testes do publicador, sem inflar a primeira migration.

**TDD e evidências:** o tipo/porta e o teste foram publicados antes do adapter. O [CI Linux #76](https://github.com/Joaomagh/credpay/actions/runs/35054744312) executou 72 testes e apresentou um único erro esperado: ausência de bean `OutboxRepository`. No green, V4 e o primeiro adapter foram adicionados; o [CI Linux #77](https://github.com/Joaomagh/credpay/actions/runs/35054907592) executou 73 testes sem falhas, erros ou skips e gerou o JAR. O adapter passou a se chamar `OutboxJdbcRepository` quando suas operações SQL explícitas foram ampliadas.

**Aceite comprovado:** depois de commit real, a leitura SQL preserva `eventId`, `aggregateId`, tipo, versão, JSON e instante; `published_at` permanece nulo. A inspeção do schema comprova que somente `published_at` aceita nulo.

**Limites atuais:** não há publicador, scheduler, claim/lease entre réplicas, contador de tentativas, retry com backoff ou retenção. Ler e marcar são operações disponíveis, mas nenhuma rotina as coordena automaticamente.

**Próximo:** publicar um evento pendente com confirmação do RabbitMQ e marcar `published_at` somente após `ack` sem retorno.

### 8.19 Geração atômica de `TransacaoCriada`

- **Refactor preparatório:** `CriarTransacaoService` passou a receber porta da outbox, `Clock` e `ObjectMapper` sem mudar comportamento; os 6 testes unitários permaneceram verdes e toda a suíte compilou.
- **Red:** os 6 casos unitários executaram; somente as 2 primeiras criações falharam porque `OutboxRepository.adicionar` não foi chamado. Replay e conflito já confirmaram zero eventos.
- **Green unitário:** depois de persistir a primeira transação, o caso de uso cria um UUID de evento, usa instante UTC do relógio injetado, serializa o envelope v1 e o adiciona à outbox. Os 6 casos passaram, preservando inclusive a escala textual de `amount`.
- **Atomicidade comprovada:** teste PostgreSQL simula falha da porta depois da inserção e confirma rollback de `transacoes` e `idempotencias_transacao`. Os testes HTTP confirmam exatamente um evento após primeira criação seguida de replay ou conflito.
- **Validação local:** teste unitário focado verde e `test-compile` verde.
- **Evidência:** [PR #41](https://github.com/Joaomagh/credpay/pull/41); [CI Linux #80](https://github.com/Joaomagh/credpay/actions/runs/35172677557) verde para `59e0fc9`, com 74 testes sem falhas, erros ou skips e JAR gerado.
- **Limites:** o evento só é persistido; nenhum processo lê ou publica a outbox. Não há RabbitMQ, marcação de publicação, retry ou DLQ.
- **Próximo:** definir a baseline mínima da mensageria e do publicador antes de adicionar Spring AMQP.

### 8.20 Leitura e marcação da outbox

- **Contrato:** `OutboxRepository.buscarPendentes(limite)` retorna somente eventos com `published_at` nulo, limitado e ordenado por `occurred_at`, depois `event_id`; `marcarPublicado(eventId, publicadoEm)` preenche o instante somente se o registro ainda estiver pendente.
- **Red:** o teste de integração foi escrito primeiro e a compilação falhou apenas pela ausência das duas operações na porta. Uma tentativa anterior de execução nem chegou ao build por bloqueio de rede e não foi considerada evidência red.
- **Green local parcial:** todas as fontes e os 11 arquivos de teste compilaram. A execução Testcontainers local permaneceu bloqueada pela indisponibilidade conhecida do Docker Desktop.
- **Feedback do primeiro CI:** o [CI Linux #90](https://github.com/Joaomagh/credpay/actions/runs/35180801615) comprovou seleção, limite e ordem, mas revelou que `JSONB` normaliza espaços. O teste foi corrigido para comparar a árvore JSON, pois whitespace não pertence ao contrato.
- **Evidência final:** [PR #44](https://github.com/Joaomagh/credpay/pull/44); [CI Linux #91](https://github.com/Joaomagh/credpay/actions/runs/35180962736) verde com 77 testes, zero falhas, erros ou skips e JAR gerado.
- **Limites:** sem publicação, scheduler, concorrência entre pollers, claim/lease, retry, backoff ou retenção. A marcação ainda não está ligada a confirmação do broker.

## 9. Mensageria e tratamento de falhas

**Estado:** ambos os serviços possuem outbox e publicação confirmada com scheduler opt-in de réplica única. A topologia de entrada, listener, retry e DLQ do processador ainda não foram implementados.

### 9.1 Topologia mínima

| Recurso | Nome | Tipo/propriedades | Responsável |
|---|---|---|---|
| exchange de eventos | `credpay.transacoes.v1` | direct, durável, não auto-delete | `transacoes-service` |
| routing key | `transacao.criada.v1` | literal versionado | contrato compartilhado |
| fila de processamento | `credpay.processamento.transacao-criada.v1` | quorum, durável, não exclusiva, não auto-delete; ainda não declarada | `processamento-service` |
| dead-letter exchange | `credpay.processamento.dlx.v1` | direct, durável, não auto-delete; ainda não declarada | `processamento-service` |
| dead-letter routing key | `transacao.criada.dlq.v1` | literal versionado; ainda não aplicado | `processamento-service` |
| dead-letter queue | `credpay.processamento.transacao-criada.dlq.v1` | quorum, durável, não exclusiva, não auto-delete; ainda não declarada | `processamento-service` |
| exchange do resultado | `credpay.processamento.v1` | direct, durável, não auto-delete; implementada | `processamento-service` |
| routing key do resultado | `transacao.processada.v1` | literal versionado; implementado | contrato compartilhado |

O produtor declara somente sua exchange. A fila, binding, DLX e DLQ pertencem ao consumidor; o teste do produtor usará uma fila efêmera exclusiva ligada à exchange, sem fazê-lo depender da topologia interna do serviço futuro. Filas quorum em um container de nó único comprovam configuração e comportamento, não alta disponibilidade.

### 9.2 Publicação confiável da outbox

- mensagens serão persistentes, com `contentType=application/json`, `messageId=eventId`, `type=TransacaoCriada` e `correlationId` igual ao ID da transação;
- `RabbitTemplate` usará `mandatory=true`, publisher returns e confirms correlacionados; o `CorrelationData` usará `eventId`;
- a outbox só receberá `published_at` depois de `ack` do broker e ausência de retorno por falta de rota;
- `nack`, return, exceção de conexão ou timeout de 5 segundos mantêm o registro pendente para nova tentativa; nenhum desses casos apaga o evento;
- a publicação será pelo menos uma vez. Uma queda depois do `ack` e antes do update pode publicar novamente; consumidores deduplicarão por `eventId`;
- a primeira versão processará no máximo 20 eventos por lote, em ordem de `occurred_at` e `event_id`. Execução concorrente por múltiplas réplicas fica bloqueada até existir claim/lease testado;
- retry do produtor ocorre pela permanência na outbox. Contador, backoff persistido e alerta entram quando o poller for implementado e medido, sem inventar exatamente uma vez.

Publisher confirms cobrem produtor → broker e são independentes do ack do consumidor. Mensagens obrigatórias sem rota precisam ser tratadas por publisher returns; um `ack` isolado não prova roteamento. Essas decisões seguem as referências oficiais de [confirms/acks](https://www.rabbitmq.com/docs/confirms) e [Spring AMQP confirms/returns](https://docs.spring.io/spring-amqp/reference/amqp/template.html).

### 9.3 Consumo, retry e DLQ parciais

O contrato refinado de B04.1 está na seção 9.28. O listener opt-in usa ack pelo container após sucesso/commit e prefetch `10`. Payload inválido e conflito de identidade são rejeitados sem requeue; demais falhas recebem até três tentativas totais, com esperas configuradas de 1 e 2 segundos, e rejeição segura ao esgotar (B04.7–B04.9). Replay equivalente e reentrega real após fechamento da conexão entre commit/ack preservaram resultado/outbox no broker real (B04.6/B04.10). Não há exactly-once: deduplicação durável continua necessária. Consumo operacional permanece desligado até provisionamento/verificação da política e fechamento das lacunas de dead-lettering.

DLQ não é garantia absoluta de entrega: o dead-lettering também pode falhar. Em quorum queues, a estratégia padrão é `at-most-once`; `at-least-once` exige configuração adicional e prova, conforme a seção 9.28. Um container de nó único não prova alta disponibilidade nem recuperação de falhas de quorum. Referências: [Quorum Queues](https://www.rabbitmq.com/docs/quorum-queues) e [Dead Letter Exchanges](https://www.rabbitmq.com/docs/dlx).

### 9.4 Dependências e ambiente aprovados

| Item | Baseline |
|---|---|
| cliente Spring | `spring-boot-starter-amqp`, versão gerenciada pelo Spring Boot 3.5.16; Spring AMQP 3.2.12 |
| teste RabbitMQ | `org.testcontainers:rabbitmq`, escopo test, versão gerenciada 1.21.4 |
| broker de teste | RabbitMQ `4.3.5-management-alpine`, com digest multi-arquitetura verificado e fixado no incremento de implementação |
| credenciais | somente valores fictícios do container; nenhuma credencial versionada |

Não será adicionado `spring-boot-testcontainers`, Awaitility separado, cliente RabbitMQ direto ou framework de schema. A versão da imagem acompanha a série 4.3 atualmente suportada, conforme [ciclo oficial](https://www.rabbitmq.com/release-information); tag flutuante e `latest` são proibidas.

### 9.5 Critérios de testes incrementais

1. container real inicia e a aplicação conecta com host/porta dinâmicos;
2. exchange durável existe e uma fila exclusiva de teste recebe `TransacaoCriada` pela routing key aprovada;
3. publicador envia payload e propriedades corretos, recebe confirm e marca outbox;
4. mensagem sem rota, `nack`, timeout ou broker indisponível não marca outbox;
5. retomada publica um pendente e tolera a janela de duplicação;
6. consumidor futuro deduplica reentrega e encaminha falha final à DLQ.

Cada item entra em um ciclo próprio. Os itens 1 a 3 e 5 estão comprovados; o item 4 cobre ausência de rota e `nack`, mas timeout e broker indisponível ainda carecem de prova vertical. O item 6 pertence ao futuro consumidor.

### 9.6 Implementação da topologia do produtor

- **Dependências:** `spring-boot-starter-amqp` em produção e `org.testcontainers:rabbitmq` em teste, ambas nas versões gerenciadas pela baseline aprovada.
- **Broker de teste:** RabbitMQ `4.3.5-management-alpine` fixado por digest e declarado como imagem compatível com o módulo Testcontainers.
- **Topologia:** `RabbitMqConfiguration` expõe a exchange direct `credpay.transacoes.v1`, durável e não auto-delete, além da routing key versionada `transacao.criada.v1`. Nenhuma fila do futuro consumidor foi criada no produtor.
- **Teste de integração:** uma fila exclusiva e efêmera é ligada à exchange; uma mensagem persistente percorre o broker real e é recebida pela routing key aprovada.
- **Preparação do red:** os CI #83 e #84 revelaram, respectivamente, a necessidade de declarar a imagem versionada como substituta compatível e de isolar o slice de mensageria das auto-configurações de banco. Essas falhas de infraestrutura de teste não foram registradas como red funcional.
- **Red válido:** o [CI Linux #85](https://github.com/Joaomagh/credpay/actions/runs/35179836927) iniciou o container e o contexto isolado, então falhou exclusivamente pela ausência do bean `DirectExchange` esperado.
- **Green:** o [CI Linux #87](https://github.com/Joaomagh/credpay/actions/runs/35180304621) executou 75 testes sem falhas, erros ou skips e gerou o JAR. A asserção usa `receivedDeliveryMode`, propriedade de entrada do Spring AMQP, para comprovar a persistência da mensagem recebida.
- **Limites daquele incremento:** a aplicação ainda não lia pendências nem configurava `mandatory`, returns ou confirms; essas capacidades entraram no incremento seguinte. A fila efêmera prova roteamento, não alta disponibilidade nem consumo de negócio.

### 9.7 Publicação confirmada de uma pendência

- **Orquestração:** `PublicarOutboxService.publicarProximo()` busca no máximo uma pendência, delega a publicação e marca `published_at` usando relógio UTC somente quando a porta retorna sucesso. Ausência, `nack` ou retorno preservam o registro pendente.
- **Mensagem:** `RabbitMqPublicadorEvento` envia o payload UTF-8 como `application/json`, persistente, com `messageId=eventId`, `type=eventType`, `correlationId=aggregateId` e `CorrelationData.id=eventId`.
- **Confiabilidade:** a configuração habilita confirm correlacionado, publisher returns e `mandatory`. O adapter aguarda até 5 segundos; sucesso exige `ack` e ausência de `ReturnedMessage`. Interrupção restaura o status da thread; timeout/erro de confirmação é propagado com contexto e não marca a outbox.
- **Red/green:** o caso de uso e o adapter nasceram de dois reds focados pela ausência dos respectivos tipos. Depois da implementação mínima, cada conjunto executou 3 testes unitários verdes; toda a suíte compilou localmente.
- **Integração:** uma fila efêmera comprova contrato e roteamento no broker real; sem binding, a mensagem obrigatória é retornada e a publicação falha.
- **Evidência:** [PR #45](https://github.com/Joaomagh/credpay/pull/45); [CI Linux #94](https://github.com/Joaomagh/credpay/actions/runs/35181807986) verde com 85 testes, zero falhas, erros ou skips e JAR gerado.
- **Limites:** não há scheduler, lote, retry/backoff persistido, claim/lease, coordenação entre réplicas, consumidor ou DLQ. O método precisa ser acionado explicitamente e processa somente um evento.

### 9.8 Lote manual limitado

- `PublicarOutboxService.publicarLote()` solicita no máximo 20 pendências, preservando a ordem fornecida pelo repository.
- Cada evento é marcado individualmente somente depois da confirmação. A primeira publicação que retorna `false` interrompe o lote; o evento falho e todos os seguintes permanecem pendentes.
- **Red:** os dois cenários novos não compilaram porque `publicarLote()` ainda não existia.
- **Green:** o teste focado executou 5 casos sem falhas; o [CI Linux #97](https://github.com/Joaomagh/credpay/actions/runs/35182480647) validou 87 testes, zero falhas, erros ou skips e gerou o JAR.
- **Limites:** o lote continua manual. Não há scheduler, paralelismo, claim/lease, backoff ou coordenação entre réplicas; executar mais de uma instância publicadora pode causar publicação concorrente do mesmo evento.

### 9.9 Scheduler opt-in de réplica única

- `OutboxSchedulingConfiguration` somente existe quando `credpay.outbox.publisher.enabled=true`; o padrão versionado é `false`.
- `OutboxPublisherScheduler` chama o lote com `fixedDelay`, usando `credpay.outbox.publisher.interval` (`PT1S` por padrão). O mesmo intervalo é usado como atraso inicial, evitando publicação durante o bootstrap imediato.
- Variáveis de ambiente: `CREDPAY_OUTBOX_PUBLISHER_ENABLED` e `CREDPAY_OUTBOX_PUBLISHER_INTERVAL`.
- **Red:** quatro testes não compilaram pela ausência da configuração e do scheduler.
- **Green:** os testes focados comprovaram ausência por padrão, criação por opt-in, delegação ao lote e placeholder do intervalo. O job `Maven verify` do [CI Linux #100](https://github.com/Joaomagh/credpay/actions/runs/35182848801) executou 91 testes sem falhas, erros ou skips e gerou o JAR.
- **Limite operacional:** `fixedDelay` evita sobreposição apenas dentro da mesma JVM. Sem claim/lease ou lock distribuído, habilitar o scheduler em mais de uma réplica pode publicar o mesmo evento concorrentemente e não é suportado.

### 9.10 Fluxo vertical de publicação comprovado

- **Cenário:** `PublicarOutboxIntegrationTest` inicia PostgreSQL e RabbitMQ com as imagens fixadas, mantém o scheduler desabilitado e usa os casos de uso reais, sem mocks ou transação externa de teste.
- **Aceite:** após criar `123.450 BRL`, o teste consulta a transação e o evento já confirmados no banco, observa `published_at` nulo, publica o lote e recebe o envelope completo e suas propriedades AMQP. O instante de publicação fica persistido; um segundo lote retorna zero, preserva o instante e não entrega outra mensagem.
- **Preparação corrigida:** o [CI #103](https://github.com/Joaomagh/credpay/actions/runs/35285010132) chegou à segunda leitura da fila, mas a fixture `auto-delete` já tinha sido removida após encerrar o primeiro consumidor. A fila de teste passou a ser exclusiva, com nome único e remoção no `finally`, permitindo as duas leituras. Isso corrigiu a fixture, não um defeito do publicador; [referência RabbitMQ](https://www.rabbitmq.com/docs/queues#temporary-queues).
- **Evidência:** [PR #48](https://github.com/Joaomagh/credpay/pull/48); [CI #104](https://github.com/Joaomagh/credpay/actions/runs/35285219623) com 92 testes, zero falhas, erros ou skips e JAR gerado. Compilação local verde; Docker local indisponível. Teste de regressão de comportamento existente, sem novo red/green de produção.
- **Revisão documental:** README corrigido para incluir `Idempotency-Key`, configuração RabbitMQ e scheduler; o estado atual de eventos substituiu afirmações antigas de implementação pendente.
- **Limites:** o teste comprova o caminho de sucesso do produtor e a ausência de republicação após a marcação. Não comprova recuperação de falhas, processamento do consumidor ou entrega exatamente uma vez.

### 9.11 Recuperação após ausência de rota

- **Cenário:** o segundo caso de `PublicarOutboxIntegrationTest` cria uma transação e mantém sua mensagem sem binding na primeira tentativa de publicação. O broker confirma o recebimento, mas devolve a mensagem obrigatória por ausência de rota.
- **Falha segura:** a primeira execução do lote retorna zero, mantém `published_at` nulo e preserva o mesmo `event_id` e payload na única linha da outbox.
- **Recuperação:** depois que o teste cria o binding aprovado, a nova execução publica exatamente o registro pendente. A fila recebe o mesmo `event_id` e JSON semanticamente equivalente, e somente então `published_at` é preenchido. Uma terceira execução não republica o evento já marcado.
- **Isolamento:** a base é limpa antes de cada cenário e cada fila exclusiva possui nome único e remoção explícita, evitando dependência da ordem de execução.
- **Evidência:** [PR #49](https://github.com/Joaomagh/credpay/pull/49); [CI Linux #107](https://github.com/Joaomagh/credpay/actions/runs/35286184441) verde. O teste nasceu como regressão de um comportamento de produção já implementado; por isso não houve red de produção artificial.
- **Limites:** o cenário comprova retorno por ausência de rota e recuperação posterior. Indisponibilidade do broker e timeout continuam sem prova vertical; consumidor, deduplicação e DLQ ainda não existem.

### 9.12 Baseline de processamento idempotente

**Estado:** contrato definido em 2026-09-30; persistência e idempotência sequencial/concorrente foram comprovadas em B02.3–B02.6. A outbox persiste a intenção na mesma transação do resultado desde B03.3; a publicação opt-in foi comprovada em B03.7–B03.10; consumo ainda está pendente.

#### Identidade e equivalência

| Situação | Contrato da v1 |
|---|---|
| Primeiro `eventId` e primeira `transactionId` válidos | decidir com a política vigente e registrar o resultado uma única vez |
| Mesmo `eventId`, conteúdo conhecido equivalente | devolver o resultado persistido, sem nova decisão, novo instante ou novo evento de saída |
| Mesmo `eventId`, conteúdo divergente | conflito permanente; preservar o resultado original, sem sobrescrever |
| Outro `eventId` para `transactionId` já processada | conflito permanente, mesmo com valor equivalente; o produtor v1 gera um único evento lógico por transação |
| Política alterada ou moeda removida depois do commit | replay usa decisão e limite persistidos, sem consultar a política atual; a aplicação ainda precisa de configuração válida para iniciar |
| Primeira tentativa revertida | não existe decisão confirmada; nova tentativa usa política vigente |

A equivalência considera `eventType`, `eventVersion`, `occurredAt` como instante, `correlationId`, `data.transactionId`, valor decimal, moeda e estado de entrada. Valor usa `BigDecimal.compareTo`; códigos de moeda seguem o contrato de entrada v1. Ordem/espaços do JSON, escala decimal equivalente e campos desconhecidos compatíveis não causam conflito. Não haverá fingerprint do JSON bruto. IDs, tipos e relação `correlationId = transactionId` serão validados antes do caso de uso; a comparação nunca legitima envelope inválido.

A escolha de conflito para um novo evento da mesma transação evita inventar aliases ou reprocessamento financeiro na v1. Um futuro fluxo de reprocessamento exigirá contrato próprio, não reutilização silenciosa de identidade. A chave HTTP `Idempotency-Key` não cruza essa fronteira.

#### Resultado e atomicidade planejados

O banco próprio do `processamento-service` é a autoridade, com unicidade para `eventId` recebido e `transactionId`. Consulta seguida de inserção, sem controle no banco, não basta. Concorrência e conflitos sem sobrescrita já foram comprovados em B02.6; a intenção de saída já é durável desde B03.3, mas o listener ainda não foi ativado.

O registro durável conserva campos semânticos da entrada, valor/moeda, limite efetivamente aplicado, `APROVADA` ou `REJEITADA`, instante de processamento e identidade do evento de saída. Resultado, marca de processamento da entrada e outbox de `TransacaoProcessada` são confirmados na mesma transação local; falha em qualquer escrita reverte todas. O formato mínimo do evento está aprovado acima e é gravado como JSONB na outbox própria.

Não há publicação AMQP direta dentro dessa transação nem acesso ao banco do outro serviço. O publicador da outbox envia o mesmo evento nas novas tentativas. A intenção de saída já é durável; consumir a entrada operacionalmente ainda depende do contrato de validação, ack, retry e DLQ do listener. Um resultado isolado não autoriza ack da mensagem real.

#### Confirmação e falhas

O listener futuro chamará um caso de uso transacional e só retornará com sucesso depois do commit confirmado. O `AcknowledgeMode.AUTO` do container Spring será usado após sucesso, não o `autoAck`/no-ack do protocolo RabbitMQ. Commit de banco e ack não são uma transação distribuída: falha nessa janela é resolvida por reentrega idempotente. Referências: [acks do RabbitMQ](https://www.rabbitmq.com/docs/confirms) e [transações Spring AMQP](https://docs.spring.io/spring-amqp/reference/amqp/transactions.html).

| Cenário negativo | Resultado exigido | Evidência futura |
|---|---|---|
| Falha ao gravar resultado ou outbox | rollback integral; nenhum ack de sucesso | PostgreSQL real e falha injetada na segunda escrita |
| Queda após commit, antes do ack | reentrega encontra resultado original e uma única intenção de saída | PostgreSQL + RabbitMQ, nova entrega e contagem no banco |
| Duas entregas equivalentes concorrentes | uma decisão/saída durável; outra retorna o mesmo resultado após resolver disputa | teste concorrente com transações distintas e constraints reais |
| Mesmo evento divergente ou nova identidade para transação concluída | conflito sem modificar resultado/outbox; erro permanente observável, encaminhado à DLQ sem retry transitório | teste de conflito, destino da mensagem e inspeção dos registros originais |
| Política muda após resultado confirmado | replay preserva limite, status, instante e identidade de saída | alterar política entre duas entregas |
| Moeda sem política na primeira tentativa | erro operacional, sem resultado financeiro; tentativas limitadas e DLQ/replay após correção | teste de falha, correção de configuração e replay controlado |
| JSON inválido ou versão incompatível | não decidir; encaminhamento permanente conforme política de DLQ | broker real e payload inválido |
| Publicação da saída repete após queda | evento mantém identidade; futuro consumidor do resultado também deduplica | teste de republicação e aplicação idempotente no produtor |

Falhas operacionais, ausência de política e mensagens na DLQ não são automaticamente `REJEITADA` nem `FALHOU`. B04.9 comprovou recuperação após falha controlada/rollback e esgotamento na integração; isso não prova replay operacional após correção de configuração nem todos os tipos de indisponibilidade. Os limites de dead-lettering continuam válidos. Não apagar resultados/marcas de deduplicação na v1; retenção depende de um horizonte de replay futuro. Banco crescente, falhas de DLQ e garantia apenas pelo menos uma vez permanecem riscos explícitos.

**Primeiro slice de implementação:** produzir um snapshot imutável de valor, moeda, limite e decisão, com teste de alteração posterior da política. Sem banco/identidade de evento nessa etapa; isso não prova idempotência ou durabilidade.

**Revisão documental:** P.O. e dev sênior revisaram o contrato em 2026-09-30. A alternativa de aceitar novo `eventId` como alias da mesma transação foi descartada por não ser exigida pelo produtor v1; o encaminhamento dos conflitos permanentes à DLQ foi explicitado. Validação deste incremento: diff, UTF-8 e links locais; não há teste de runtime nem evidência de persistência adicionada.

### 9.13 Baseline de persistência própria do processador — B02.2

**Estado:** decisão documental integrada e materializada no incremento B02.3. As dependências aprovadas, a configuração obrigatória, a migration V1, a porta e o adapter foram adicionados sem H2 ou banco alternativo. O Docker local permaneceu indisponível e nenhum start, reset ou container foi executado; o CI Linux comprovou o round-trip com PostgreSQL real.

#### Escolha de acesso e dependências

O registro de processamento usa JPA para mapear o agregado persistido e participar da transação local Spring. É a mesma estratégia já comprovada no primeiro serviço e reduz novas variáveis. A futura outbox poderá usar JDBC quando seleção ordenada, claim e update condicional exigirem SQL explícito. Conflitos/concorrência também poderão exigir constraint, lock ou SQL nativo específico, sem invalidar o mapeamento JPA do agregado nem criar um repository genérico.

| Dependência adicionada em B02.3 | Escopo | Versão gerenciada já comprovada | Motivo |
|---|---|---:|---|
| `spring-boot-starter-data-jpa` | compile | Spring Boot 3.5.16 / Hibernate 6.6.53.Final | entidade, transação local e adapter do resultado |
| `org.postgresql:postgresql` | runtime | 42.7.11 | driver do banco próprio |
| `org.flywaydb:flyway-core` | compile | 11.7.2 | migrations versionadas |
| `org.flywaydb:flyway-database-postgresql` | runtime | 11.7.2 | suporte Flyway específico |
| `org.testcontainers:junit-jupiter` | test | 1.21.4 | ciclo de vida no JUnit |
| `org.testcontainers:postgresql` | test | 1.21.4 | PostgreSQL real descartável |

As versões vêm do parent Spring Boot existente, sem BOM ou sobrescrita. B02.3 adicionou o conjunto junto do primeiro teste/adapter. Não entram Spring AMQP, H2, `spring-boot-testcontainers`, biblioteca de retry, Lombok ou outro mapper.

Imagem usada no teste e já executada pelo produtor: `postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0`, correspondente a PostgreSQL 17.11 Bookworm. Reutilizar o artefato reduz variáveis, mas o teste do segundo serviço ainda precisa provar sua própria compatibilidade no CI.

#### Configuração e propriedade

O banco pertence exclusivamente ao `processamento-service`, sem foreign key, consulta ou credencial do banco do produtor. A aplicação exige `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` e `SPRING_DATASOURCE_PASSWORD`, sem credencial ou banco em memória padrão. Flyway cria e evolui o schema; Hibernate usa `ddl-auto=validate` e `open-in-view=false`. Testes fornecem conexão e credenciais fictícias via `@DynamicPropertySource`.

O health check recebe PostgreSQL descartável e mantém DataSource/Flyway ativos; nenhuma exclusão global deixa o contexto artificialmente verde.

#### Migration V1

Uma única tabela `processamentos` reúne a marca da entrada e o resultado, pois a v1 não aceita aliases de evento.

| Campo | Tipo | Invariante |
|---|---|---|
| `event_id` | `uuid` | PK; identidade única da entrada |
| `transaction_id` | `uuid` | único e obrigatório; uma decisão de negócio por transação |
| `event_type` | `varchar(64)` | literal `TransacaoCriada` |
| `event_version` | `integer` | literal `1` na v1 |
| `event_occurred_epoch_second` | `bigint` | segundos do `Instant` recebido, sem redução de precisão |
| `event_occurred_nano` | `integer` | `0..999999999`; recompõe exatamente o `Instant` |
| `correlation_id` | `uuid` | obrigatório e igual a `transaction_id` na v1 |
| `input_status` | `varchar(16)` | literal `PENDENTE` |
| `valor` | `numeric` | positivo, finito e sem escala fixa |
| `moeda` | `varchar(3)` | código de entrada preservado; formato `[A-Z]{3}` |
| `limite_aplicado` | `numeric` | positivo, finito e sem escala fixa |
| `resultado` | `varchar(16)` | `APROVADA` ou `REJEITADA` |
| `processed_at` | `timestamptz(6)` | instante atribuído à primeira decisão persistida, normalizado para micros |
| `output_event_id` | `uuid` | único e obrigatório; identidade congelada do futuro `TransacaoProcessada` |

PostgreSQL `timestamptz` possui precisão menor que os nanos de `Instant`. Separar epoch second/nano preserva equivalência temporal sem comparar texto bruto, ordem JSON ou whitespace e sem alterar silenciosamente `TransacaoCriada` v1. `processed_at` é gerado pelo `Clock` antes do commit, será truncado com `Instant.truncatedTo(ChronoUnit.MICROS)` antes de construir/persistir o registro e não participa da equivalência da entrada.

Constraints nomeadas protegem unicidades, literais, relação de correlação, intervalo de nanos e valores numéricos positivos/finitos. Para `valor` e `limite_aplicado`, exigem `> 0` e excluem explicitamente `NaN`, `Infinity` e `-Infinity`; a ordenação especial de `numeric` torna `> 0` isolado insuficiente. A validação completa ISO 4217 continua na aplicação; a constraint de três letras não afirma que todo código é moeda existente. Não há limpeza automática: retenção precisa de horizonte de replay aprovado.

A tabela ainda não é a outbox. B02.3 armazena `output_event_id` com listener desabilitado, sem contrato/payload de saída. B03 reutilizará exatamente essa identidade ao adicionar a intenção de saída em migration própria e na mesma transação do resultado, antes de qualquer listener real ser habilitado.

#### Primeiro round-trip aceito

O primeiro ciclo TDD implementa somente a porta, adapter JPA, entidade e V1 necessários para:

1. iniciar PostgreSQL descartável fixado por digest e aplicar a migration de produção;
2. gravar um resultado completo numa transação e concluir o commit;
3. limpar/separar o contexto de persistência e ler em outra transação;
4. comprovar `event_id`, `transaction_id`, `output_event_id`, campos conhecidos da entrada, valor/escala, moeda, limite/escala, resultado, `processed_at` já normalizado e reconstrução exata de `occurredAt` com fixture que contenha nanos além de micros, por exemplo `2026-09-30T12:34:56.123456789Z`;
5. manter `ddl-auto=validate`, sem H2, mocks ou rollback externo do teste.

Esse round-trip prova durabilidade básica depois de commit. Não conclui B02 nem prova deduplicação, equivalência, concorrência, rollback, outbox ou confirmação RabbitMQ. Ciclos posteriores cobrirão ausência, constraints por SQL direto, conflitos sem sobrescrita, falha/rollback e duas transações concorrentes antes de B02 ser marcado concluído.

O adapter recebe `ProcessamentoRegistrado`, valor imutável que representa o registro completo; não uma longa lista de parâmetros. `ResultadoProcessamento` continua sendo o snapshot puro da decisão e não foi transformado em entidade JPA.

### 9.14 Primeiro round-trip PostgreSQL do resultado — B02.3

- **Red:** após adicionar somente o teste e as seis dependências aprovadas, `testCompile` falhou com seis erros limitados à ausência de `ProcessamentoRegistrado`, `ProcessamentoRepository` e `ProcessamentoJpaRepository`. O Docker não foi alcançado nessa etapa.
- **Green de implementação:** foi criado um registro imutável completo na camada de aplicação, uma porta de repository, um adapter JPA e uma entidade separada. A migration V1 cria `processamentos` com identidades únicas, literais versionados, correlação, valores positivos/finitos e precisão temporal conforme a baseline. Não há H2, fallback, listener, endpoint ou outbox.
- **Prova desenhada:** o teste usa a migration de produção num PostgreSQL fixado por digest, grava e conclui uma transação, lê em outra e compara identidades, literais, escalas decimais, moeda, decisão, `processedAt` e `occurredAt` com nanos preservados por epoch second/nano.
- **Verificação local:** `test-compile` passou; 35 testes sem infraestrutura passaram, sem falhas ou skips. O teste focado chegou ao Testcontainers e terminou com `Could not find a valid Docker environment`, porque o pipe `dockerDesktopLinuxEngine` não existe nesta estação. Isso é bloqueio de ambiente, não green do round-trip; a suíte completa local não foi declarada aprovada.
- **CI e aceite:** o [Processing Service CI #34](https://github.com/Joaomagh/credpay/actions/runs/36802312464) executou o job `Maven verify` com sucesso no SHA `a6b2101`, comprovando Flyway, Hibernate `validate`, health e round-trip no PostgreSQL real. O SHA documental final também deve permanecer verde antes do merge.
- **Revisão:** a revisão sênior identificou que o slice JPA poderia carregar a configuração obrigatória de limites sem fixture. O teste recebeu apenas `credpay.processamento.limites.BRL=100.00`; a aplicação continua sem limite padrão ou fallback.
- **Limites:** este slice prova somente durabilidade básica depois do commit. Não prova colisões sem sobrescrita, rollback, concorrência, equivalência/replay, atomicidade com outbox ou mensageria.
- **Próximo:** comprovar colisões de `eventId`, `transactionId` e `outputEventId` e rollback após `flush`, preservando o registro original e sem ampliar para concorrência ou replay.

### 9.15 Integridade e rollback do resultado — B02.4

- **Tipo de ciclo:** caracterização de controles já entregues pela migration V1. Não houve red honesto nem mudança de produção; fabricar uma falha exigiria enfraquecer deliberadamente constraints já integradas.
- **Colisões:** três cenários gravam e commitam o original, tentam em outra transação um registro divergente que repete somente `eventId`, `transactionId` ou `outputEventId`, e exigem respectivamente `processamentos_pkey`, `uq_processamentos_transaction_id` ou `uq_processamentos_output_event_id` numa `DataIntegrityViolationException`.
- **Não sobrescrita:** depois de cada conflito, uma terceira transação relê o registro pelo `eventId` original e compara os 13 componentes de `ProcessamentoRegistrado`, incluindo escalas, instantes, status e identidades.
- **Rollback:** outro cenário insere, força `EntityManager.flush()`, marca a transação para rollback e exige `Optional.empty()` numa leitura posterior.
- **Verificação:** `test-compile` passou e 35 testes sem infraestrutura ficaram verdes localmente, sem falhas, erros ou skips. O Docker local não foi iniciado nem resetado. O [Processing Service CI #37](https://github.com/Joaomagh/credpay/actions/runs/36802986688) executou o `verify` completo com sucesso no SHA `e8db337`, incluindo os cinco cenários PostgreSQL.
- **Limites:** esses cenários provam controles de armazenamento, não replay reconhecido pela aplicação, concorrência, exatamente uma vez, listener ou outbox.
- **Próximo:** implementar idempotência sequencial na aplicação: primeira decisão persistida, replay equivalente devolvendo o snapshot original e conflitos explícitos sem sobrescrita.

### 9.16 Idempotência sequencial da aplicação — B02.5

- **Red:** o teste de aplicação foi escrito antes dos tipos de entrada, caso de uso e conflito; `testCompile` falhou pela ausência desses tipos. A consulta `existePorTransactionId` teve red separado, com apenas o método ausente depois de corrigida a tipagem do teste.
- **Green local:** `RegistrarProcessamentoService` consulta primeiro `eventId` e depois `transactionId`; somente uma entrada nova consulta a política, captura relógio e gera `outputEventId`. O método transacional registra a primeira decisão. A entrada tipada conserva IDs, instante com nanos, valor e moeda; a equivalência compara o valor com `BigDecimal.compareTo`, sem consultar limite ou resultado de uma nova política.
- **Conflitos:** mesmo `eventId` divergente e outro `eventId` para transação já concluída produzem `ConflitoProcessamentoException`; o teste parametrizado varia valor, moeda, instante ou transação/correlação e comprova que o registro original não é sobrescrito. A V1 mantém unicidade no banco.
- **Validação da entrada:** correlação divergente e valor zero tiveram red de execução (nenhuma exceção); as guardas mínimas foram implementadas e os cenários ficaram verdes.
- **Verificação:** nove testes do novo caso de uso e os 35 testes puros anteriores passaram localmente. O teste vertical executa duas chamadas separadas ao serviço, releitura após commit e confirma uma única linha em PostgreSQL. Docker Desktop local não oferece o pipe `dockerDesktopLinuxEngine`. O [Processing Service CI #40](https://github.com/Joaomagh/credpay/actions/runs/36904356924) executou `Maven verify` com sucesso no SHA `30fe88c`, incluindo o PostgreSQL real e o health completo.
- **Revisão:** o dev sênior identificou que um caso de uso `final` impediria o proxy Spring de aplicar `@Transactional` sem interface; a classe foi tornada extensível antes do CI. O teste de contexto com PostgreSQL é a barreira para essa configuração.
- **Limite:** a consulta seguida de inserção ainda tem uma janela de corrida. B02.5 comprova chamadas sequenciais, sem alegar idempotência concorrente, intenção de saída durável ou consumo RabbitMQ.
- **Próximo:** provar e resolver duas entregas concorrentes da mesma entrada no PostgreSQL, preservando uma decisão e uma identidade de saída.

### 9.17 Idempotência concorrente da aplicação — B02.6

- **Red:** o teste de integração abriu duas transações/conexões reais, segurou a primeira antes da decisão e exigiu observar a segunda aguardando um lock no PostgreSQL. No [Processing Service CI #43](https://github.com/Joaomagh/credpay/actions/runs/36905584307), os cinco cenários falharam pelo motivo esperado: a segunda não aguardava; os 52 testes anteriores passaram. A falha local do Testcontainers foi exclusivamente a indisponibilidade do Docker Desktop, não evidência red de comportamento.
- **Green:** `RegistrarProcessamentoService` adquire locks transacionais para `eventId` e `transactionId` antes de consultar ou decidir. O adapter deriva ambas as chaves de 64 bits no PostgreSQL, remove duplicatas e ordena as chaves efetivamente bloqueadas antes de chamar `pg_advisory_xact_lock`; commit ou rollback libera os locks. A transação usa `READ_COMMITTED` para a segunda chamada enxergar o resultado confirmado pela primeira. As constraints de unicidade continuam sendo a última barreira no banco.
- **Prova:** replay equivalente, inclusive valor decimal de escala diferente, retorna o snapshot original e um só `outputEventId`; valor divergente, mesmo evento para outra transação e outro evento para a mesma transação produzem conflito sem sobrescrita. Se a primeira decisão falha antes de gravar, a segunda pode decidir e persistir; erros técnicos não viram rejeição financeira. Os testes observam uma espera real em `pg_locks`, sem depender apenas de `sleep`.
- **Verificação:** 20 testes focados de aplicação passaram localmente; o [Processing Service CI #44](https://github.com/Joaomagh/credpay/actions/runs/36905901139) executou `Maven verify` com PostgreSQL real e 57 testes verdes. Na revisão, um teste de regressão revelou que ordenar UUIDs antes do hash não ordenava os recursos reais quando havia colisão; os dois cenários red falharam localmente e ficaram verdes após ordenar/deduplicar os valores `bigint`. O [Processing Service CI #46](https://github.com/Joaomagh/credpay/actions/runs/36909956773) passou no ajuste final com 59 testes, sem erros ou skips. Docker Desktop local continua indisponível.
- **Limites:** o caso de uso ainda não é chamado por listener; a saída ainda não tem contrato nem outbox. O lock serializa entradas que compartilham qualquer uma das identidades, mas não implica entrega exatamente uma vez nem coordena publicação RabbitMQ. Colisões de hash podem apenas serializar entradas não relacionadas; não autorizam replay incorreto, pois a equivalência é verificada sobre o registro persistido.
- **Próximo:** definir o contrato versionado mínimo de `TransacaoProcessada` v1 e a atomicidade de resultado + intenção de saída antes de implementar a outbox do processador.

### 9.18 Contrato da saída e atomicidade planejada — B03.1

- **Escopo da decisão:** `TransacaoProcessada` v1 segue o envelope e os campos mínimos da seção 6. O `processamento-service` será dono da exchange `credpay.processamento.v1` e publicará com a routing key `transacao.processada.v1`; o futuro `transacoes-service` será dono de sua fila, binding e política de DLQ. Nomes e configuração dessa fila serão refinados antes do consumo.
- **Unidade de trabalho:** para uma entrada nova, inserir `processamentos` e uma linha na outbox própria na mesma transação PostgreSQL. `outbox.event_id` será igual a `processamentos.output_event_id`, com unicidade e vínculo verificável; payload e `occurred_at` congelam a decisão registrada. Falha na segunda escrita ou no commit reverte ambas. Nenhum envio AMQP ocorre dentro da transação.
- **Replay:** entrada equivalente reutiliza resultado e intenção existentes, sem nova política, relógio, `eventId` ou linha de outbox. Entrada divergente preserva ambas as linhas e produz conflito. A publicação futura pode repetir o mesmo payload/`eventId` se ocorrer queda após o broker confirmar e antes de marcar a outbox: a garantia é pelo menos uma vez, não exatamente uma vez.
- **Registros preparatórios:** a V1 já contém resultados com `output_event_id`, mas sem outbox. A futura migration V2 deve criar a outbox e reconstruir uma intenção por resultado preexistente, reutilizando `output_event_id`, `processed_at`, `event_id` recebido, `transaction_id` e `status`; o teste deve iniciar de uma V1 povoada. O publicador e o listener continuarão desabilitados até a migração e o fluxo atômico serem validados, para não expor resultado sem intenção de saída. Em ambiente de demonstração, a publicação de histórico migrado será verificada antes de habilitar a saída.

| Prova exigida antes de ativar consumo/publicação | Resultado esperado |
|---|---|
| Migration V1 povoada para V2 | cada resultado anterior ganha exatamente uma intenção com o mesmo `output_event_id` e payload semanticamente correto |
| Primeira entrada nova | resultado e uma intenção ficam visíveis juntos após commit |
| Falha forçada ao inserir a intenção | resultado e intenção ausentes após rollback em nova transação |
| Replay equivalente após commit | mesmo resultado, mesmo payload e `event_id`, uma linha em cada tabela |
| Conflito concorrente | perdedora não altera resultado nem intenção vencedora |
| Publicação futura repetida | mesmo `eventId`/payload; marcação só depois de confirmação e ausência de retorno |

**Limite:** B03.1 é decisão documental, não migration, código, mensagem enviada ou consumidor. O próximo incremento implementa apenas a base persistente da outbox e seu backfill testado; a ligação transacional do caso de uso virá antes de qualquer listener.

### 9.19 Base persistente da saída — B03.2

- **Red de migration:** o teste iniciou uma V1 com resultado já confirmado, executou as migrations atuais e tentou ler `outbox_eventos`. No [CI #49](https://github.com/Joaomagh/credpay/actions/runs/36911012752), os 59 testes anteriores passaram e o novo falhou porque a tabela não existia.
- **Green da migration:** V2 cria `outbox_eventos` no banco próprio e reconstrói uma intenção por resultado V1, reutilizando `output_event_id`, `transaction_id`, `event_id` de entrada, `processed_at` e `resultado`. O payload JSONB segue `TransacaoProcessada` v1; `published_at` nasce nulo. `event_id` é PK e FK diferida para `processamentos.output_event_id`; `aggregate_id` é único. A FK é verificada no commit para permitir que o JPA adie o INSERT do resultado enquanto o JDBC grava a outbox na mesma transação. O [CI #50](https://github.com/Joaomagh/credpay/actions/runs/36911253236) comprovou o backfill em PostgreSQL real.
- **Red do adapter:** a primeira execução do teste de round-trip não chegou à asserção por falta da fixture de limite BRL no slice; o [CI #51](https://github.com/Joaomagh/credpay/actions/runs/36911579953) registrou essa falha de preparação. Corrigida a fixture, o [CI #52](https://github.com/Joaomagh/credpay/actions/runs/36911836846) iniciou o contexto e falhou porque a consulta da outbox voltou vazia.
- **Green do adapter:** `OutboxProcessamentoJdbcRepository` insere o payload JSONB e relê a intenção por `eventId`. Um teste confirma resultado e intenção na mesma transação e os relê em outra, sem `flush` artificial; o [CI #53](https://github.com/Joaomagh/credpay/actions/runs/36912080312) executou 61 testes verdes. A PK da outbox também recebeu teste de caracterização para duplicata sem sobrescrita; o [CI #54](https://github.com/Joaomagh/credpay/actions/runs/36912396698) passou com 62 testes, sem falhas, erros ou skips.
- **Limites:** o caso de uso ainda não escreve a outbox; o teste compõe as duas escritas manualmente para provar a infraestrutura. Não há publicador, confirmação RabbitMQ, listener ou ack. O backfill V1 comprova um resultado preexistente; cenários com múltiplos resultados e falhas de migração exigirão expansão se surgirem dúvidas antes de uma implantação real.
- **Próximo:** ligar a nova intenção à primeira decisão em `RegistrarProcessamentoService`, com rollback das duas escritas e replay sem nova linha, antes de habilitar consumo.

### 9.20 Resultado e intenção atômicos — B03.3

- **Red:** o teste vertical com PostgreSQL real exigiu uma intenção `TransacaoProcessada` para a primeira decisão e nenhuma segunda intenção no replay. Também injetou falha depois do INSERT da outbox para exigir rollback de resultado e intenção. No [CI #57](https://github.com/Joaomagh/credpay/actions/runs/36912931176), os 62 testes anteriores passaram; os dois cenários novos falharam porque o caso de uso não chamava a outbox.
- **Green:** `RegistrarProcessamentoService` escreve a intenção somente após construir e persistir o resultado novo, dentro da mesma transação `READ_COMMITTED`. O payload v1 reutiliza `outputEventId`, `processedAt`, `eventId` recebido e `transactionId`; contém só o estado final necessário. Replay equivalente retorna antes dessas escritas. Serialização JSON falha de modo explícito e causa rollback, sem produzir resultado financeiro alternativo.
- **Prova:** o teste relê resultado e outbox após commit, verifica o payload semântico e conta uma linha após replay. Uma outbox de teste delega o INSERT real e lança exceção em seguida; após rollback, leituras independentes não encontram nem resultado nem intenção. A FK diferida da V2 permite que o JPA conclua seu INSERT no commit sem `flush` de teste. O [CI #58](https://github.com/Joaomagh/credpay/actions/runs/36913236951) passou com 64 testes, zero falhas, erros ou skips; 20 testes unitários afetados passaram localmente.
- **Limites:** não há leitura de pendências, marcação, envio AMQP, confirms, listener nem ack. Persistir intenção não equivale a publicá-la. Docker Desktop local continua indisponível; a prova PostgreSQL veio do CI.
- **Próximo:** ler pendências em ordem e marcar publicação no banco próprio, sem conectar RabbitMQ ainda.

### 9.21 Operações da outbox de saída — B03.4

- **Red:** dois testes PostgreSQL novos criaram intenções reais e exigiram leitura por `(occurred_at, event_id)` com limite, excluindo publicadas; a marcação deveria guardar o primeiro instante mesmo quando repetida. No [CI #61](https://github.com/Joaomagh/credpay/actions/runs/36914017382), os 64 testes anteriores passaram e os dois novos falharam: lista vazia e `published_at` nulo.
- **Green:** `OutboxProcessamentoJdbcRepository` seleciona somente `published_at IS NULL`, ordena por instante e ID, aplica `LIMIT` e reutiliza o mapper do round-trip. `marcarPublicado` atualiza somente a linha ainda pendente; nova chamada ou ID ausente não altera o primeiro instante nem cria registro. O [CI #62](https://github.com/Joaomagh/credpay/actions/runs/36914275852) executou 66 testes verdes com PostgreSQL real, sem falhas, erros ou skips; 11 testes sem Docker passaram localmente.
- **Limite de segurança:** estas são operações de armazenamento, não evidência de publicação. Não existe chamador, AMQP, scheduler, claim/lease, retry ou coordenação entre réplicas. A futura marcação só poderá ocorrer após publisher confirm e ausência de mandatory return; marcar antes pode perder a mensagem. Nenhuma garantia de exatamente uma vez foi criada.
- **Próximo:** definir a baseline RabbitMQ mínima do processador, inclusive dependências concretas, exchange/routing e provas de confirmação, antes de implementar publicador.

### 9.22 Baseline RabbitMQ da saída — B03.5

**Status:** decisão documental para o `processamento-service`; nenhuma dependência ou configuração AMQP foi adicionada neste incremento. Reutiliza versões já comprovadas no `transacoes-service`, sem introduzir outro cliente ou broker.

| Item | Baseline aprovada para implementação futura | Por que é necessário |
|---|---|---|
| Cliente AMQP | `spring-boot-starter-amqp`, gerenciado por Spring Boot 3.5.16 (Spring AMQP 3.2.12) | conexão, declaração da exchange e publisher confirms/returns sem cliente paralelo |
| Teste de broker | `org.testcontainers:rabbitmq`, escopo test, versão gerenciada 1.21.4 | provar topologia e entrega/retorno em RabbitMQ real descartável |
| Imagem de teste | `rabbitmq:4.3.5-management-alpine@sha256:b3b8b7f95f5382a19f9ea33540e604f30aad081d37ad9aba72255135765373a1` | mesmo artefato fixado e já executado no CI do produtor |
| Configuração | `publisher-confirm-type=correlated`, `publisher-returns=true`, `template.mandatory=true` | distinguir recebimento pelo broker de roteamento efetivo |

O `processamento-service` será dono apenas da exchange direct durável `credpay.processamento.v1` e publicará pela routing key `transacao.processada.v1`. Não declarará fila, binding ou DLQ do `transacoes-service`; estes pertencem ao futuro consumidor. Testes da exchange usarão fila temporária exclusiva para observar entrega, sem torná-la contrato operacional.

Mensagem: JSON UTF-8 persistente, `contentType=application/json`, `messageId=eventId`, `type=TransacaoProcessada`, `correlationId=transactionId`; corpo idêntico ao payload congelado na outbox. A intenção só receberá `published_at` após `ack` correlacionado e ausência de mandatory return. `nack`, mensagem sem rota, exceção de conexão, interrupção ou timeout de 5 segundos mantêm a linha pendente. Interrupção preserva a flag da thread. O publicador não abrirá transação distribuída entre PostgreSQL e RabbitMQ.

| Prova incremental antes de habilitar publicação | Resultado esperado |
|---|---|
| Exchange declarada com broker real | direct, durável, não auto-delete; fila de teste recebe pela routing key v1 |
| Publicação confirmada e roteada | propriedades e payload preservados; marcar somente após `ack` sem return |
| Sem rota ou `nack` | mesmo `eventId` e payload permanecem pendentes |
| Timeout, conexão indisponível ou interrupção | falha observável; pendência não é apagada nem marcada |
| Queda após confirmação antes da marcação | nova tentativa usa a mesma identidade; entrega pelo menos uma vez, nunca promessa de exatamente uma vez |

Sem `spring-boot-testcontainers`, Awaitility adicional, cliente RabbitMQ direto, scheduler, claim/lease ou múltiplas réplicas publicadoras nesta baseline. O próximo incremento cobre somente dependências e topologia com teste real; envio e marcação conectados virão depois. Segue-se a mesma distinção entre confirms e consumer acks documentada na seção 9.2.

### 9.23 Topologia RabbitMQ da saída — B03.6

- **Red:** `spring-boot-starter-amqp` e `org.testcontainers:rabbitmq` foram adicionados nas versões já aprovadas; o teste com imagem por digest compilou, iniciou RabbitMQ real e falhou porque não havia bean `DirectExchange`. No [CI #65](https://github.com/Joaomagh/credpay/actions/runs/36915240876), os 66 testes anteriores passaram.
- **Green da topologia:** `RabbitMqSaidaConfiguration` declara somente `credpay.processamento.v1` como exchange direct durável, não auto-delete, e fixa a routing key `transacao.processada.v1`. O teste liga uma fila exclusiva descartável, envia uma mensagem persistente e comprova roteamento e corpo; não introduz fila operacional do consumidor.
- **Regressão descoberta:** com o starter AMQP, o health HTTP anterior retornou 503 ao tentar conectar a um broker que ainda não é dependência operacional. O [CI #66](https://github.com/Joaomagh/credpay/actions/runs/36915529611) mostrou topologia verde e somente esse health vermelho. `management.health.rabbit.enabled=false` preserva temporariamente o health baseado no PostgreSQL. Essa desativação deve ser removida quando a publicação for ligada ao fluxo operacional; não é alegação de que a indisponibilidade do broker será invisível em produção.
- **Verificação:** o [CI #67](https://github.com/Joaomagh/credpay/actions/runs/36915844357) executou 67 testes, zero falhas, erros ou skips, incluindo topologia e health HTTP. `test-compile` passou localmente; Docker Desktop continua indisponível.
- **Limites:** sem publicador, consumo, scheduler, queue/DLQ de destino, confirms aplicados à outbox ou marcação após ack. A configuração de `publisher-confirm-type`, returns e mandatory será ativada junto do primeiro publicador.
- **Próximo:** implementar e testar a publicação isolada de uma intenção, com confirm e return, sem ainda consumir pendências em lote.

### 9.24 Publicador isolado da saída — B03.7

- **Red do envio:** com a porta e o teste RabbitMQ real, o [CI #70](https://github.com/Joaomagh/credpay/actions/runs/36916566264) executou 68 testes: os 67 anteriores passaram e o novo falhou porque o bean do publicador não existia. A tentativa local parou antes do cenário porque o Docker Desktop segue indisponível; não foi contada como red de comportamento.
- **Primeiro green:** `RabbitMqPublicadorSaida` envia o payload congelado da outbox como JSON UTF-8 persistente, com `messageId=eventId`, `type=TransacaoProcessada` e `correlationId=aggregateId`. Uma `CorrelationData` própria aguarda até cinco segundos pelo confirm. O [CI #71](https://github.com/Joaomagh/credpay/actions/runs/36916845797) passou com broker real.
- **Red sem rota:** um segundo teste publicou na exchange sem binding. O [CI #72](https://github.com/Joaomagh/credpay/actions/runs/36917064130) falhou porque o broker confirmou a exchange, mas o método retornou sucesso apesar de nenhuma fila receber a mensagem.
- **Green final:** `publisher-returns=true` e `template.mandatory=true` fazem o broker devolver mensagens sem rota; sucesso exige `ack` correlacionado **e** ausência de return. O [CI #73](https://github.com/Joaomagh/credpay/actions/runs/37052099553) executou 69 testes, sem falhas, erros ou skips. `test-compile` e `git diff --check` passaram localmente.
- **Limites:** o adapter é isolado; nenhum caso de uso lê a outbox ou marca `published_at`, não há scheduler nem consumidor. `management.health.rabbit.enabled=false` permanece temporário e deve ser revisto ao conectar a publicação ao fluxo operacional. `nack`, timeout, conexão interrompida e queda entre confirm e marcação ainda pedem prova vertical; o retorno falso por falta de rota não apaga uma intenção porque ainda não há chamador.
- **Próximo:** ligar uma pendência ao publicador e marcar somente após confirmação sem retorno, com testes PostgreSQL/RabbitMQ reais e sem scheduler.

### 9.25 Ligação de uma pendência da outbox — B03.8

- **Red:** o teste vertical registrou resultado e intenção em PostgreSQL real, declarou uma fila de teste no RabbitMQ e exigiu publicação seguida de `published_at`. No [CI #76](https://github.com/Joaomagh/credpay/actions/runs/37052763137), 69 testes anteriores passaram e o novo falhou pela ausência do caso de uso de publicação.
- **Green:** `PublicarOutboxProcessamentoService.publicarProximo` lê apenas a primeira pendência, envia pelo publicador isolado e marca com `Clock` somente após retorno verdadeiro (`ack` sem mandatory return). Ausência de pendência ou retorno falso não marca. Não há `@Transactional` abrangendo banco e broker, nem scheduler. O [CI #79](https://github.com/Joaomagh/credpay/actions/runs/37053459325) passou com 70 testes, incluindo payload/identidade recebidos, instante marcado e ausência de segundo envio.
- **Falhas de fixture:** no [CI #77](https://github.com/Joaomagh/credpay/actions/runs/37053025738), a fila exclusiva de teste desapareceu antes da segunda leitura usada para verificar ausência de duplicata. A tentativa de fila transitória não exclusiva foi recusada pelo RabbitMQ 4.3 no [CI #78](https://github.com/Joaomagh/credpay/actions/runs/37053250477). Fila de teste durável com nome único, removida no `finally`, resolveu a preparação; não existe fila operacional nova.
- **Caracterização adicional:** sem binding, publicar retorna falso e conserva a única intenção sem `published_at`; ao criar a rota, nova chamada envia o mesmo `eventId` e payload e marca. O [CI #80](https://github.com/Joaomagh/credpay/actions/runs/37053660495) executou 71 testes verdes. Esse cenário foi acrescentado após o green do caso de uso, portanto não é apresentado como red de TDD independente.
- **Limites:** falta simular a queda entre confirm e marcação, que pode gerar duplicata legítima; também faltam scheduler, listener, política de múltiplas réplicas e health do broker quando o fluxo for ativado. O método existe, mas só é chamado por testes; o RabbitMQ ainda não é dependência operacional do processo em repouso.
- **Próximo:** forçar a falha entre confirmação e marcação e provar reenvio da mesma intenção, sem habilitar execução automática.

### 9.26 Recuperação entre confirmação e marcação — B03.9

- **Prova:** um wrapper de teste para a porta da outbox lança exceção controlada imediatamente antes de `marcarPublicado`, depois de o publicador obter confirmação RabbitMQ. O broker entrega a primeira mensagem, mas `published_at` continua nulo em PostgreSQL. Desarmada a falha, uma segunda chamada publica a mesma linha, com o mesmo `eventId` e payload, e registra o instante. A fila de teste é durável, única e removida no `finally`.
- **Tipo de teste:** caracterização/regressão de uma propriedade já entregue pelo desenho de B03.8; não houve red honesto nem alteração de produção. Fabricar red exigiria enfraquecer a condição de marcação já integrada. O [CI #83](https://github.com/Joaomagh/credpay/actions/runs/37054296624) executou 72 testes, zero falhas, erros ou skips, com PostgreSQL e RabbitMQ reais; `test-compile` e `git diff --check` passaram localmente.
- **Consequência:** a entrega é pelo menos uma vez; a duplicata é legítima após falha nessa janela. O consumidor futuro deverá deduplicar por `eventId`. O teste não prova recuperação automática, pois ainda não há scheduler, nem segurança com múltiplas réplicas.
- **Próximo:** ativar um scheduler opt-in para uma única réplica e incluir RabbitMQ no health quando essa publicação estiver habilitada.

### 9.27 Publicação automática opt-in da saída — B03.10

- **Red do scheduler:** `OutboxSchedulingConfigurationTest` exigiu bean somente com `credpay.outbox.publisher.enabled=true` e delegação de um disparo ao caso de uso. O teste focado local compilou e falhou apenas pela ausência do bean; o cenário desabilitado passou.
- **Green do scheduler:** configuração condicional com `@EnableScheduling`; intervalo e atraso inicial configuráveis, padrão `PT1S`. Cada disparo tenta uma pendência. O teste focado passou com dois cenários e depois acrescentou uma caracterização de disparo automático, chegando a três cenários verdes localmente e no [CI #89](https://github.com/Joaomagh/credpay/actions/runs/37055939067). Não há lote, eleição, claim ou suporte a múltiplas réplicas publicadoras.
- **Health:** com o starter AMQP e publicação desligada, o indicador RabbitMQ permanece desabilitado para preservar o health baseado no banco. Quando `CREDPAY_OUTBOX_PUBLISHER_ENABLED=true`, `management.health.rabbit.enabled` acompanha a mesma opção. O primeiro teste HTTP de `/actuator/health/rabbit` obteve 404 nos [CI #86](https://github.com/Joaomagh/credpay/actions/runs/37055059555) e [#87](https://github.com/Joaomagh/credpay/actions/runs/37055361810), mesmo após ligar o indicador: subcaminho HTTP não era evidência adequada sob a visibilidade padrão de componentes. A verificação corrigida injeta `RabbitHealthIndicator` e exige `UP` com broker real; [CI #88](https://github.com/Joaomagh/credpay/actions/runs/37055674513) e [#89](https://github.com/Joaomagh/credpay/actions/runs/37055939067) verdes.
- **Operação:** por padrão `CREDPAY_OUTBOX_PUBLISHER_ENABLED=false`; `CREDPAY_OUTBOX_PUBLISHER_INTERVAL` aceita duração como `PT1S`. Ativar somente em uma réplica e com RabbitMQ configurado. Na ausência de binding do futuro consumidor, mandatory return conserva as intenções pendentes. Não existe consumo nem conclusão ponta a ponta.
- **Próximo:** definir o contrato seguro do consumidor `TransacaoCriada` v1, incluindo fila, validação, ack após commit, retry limitado e DLQ, antes de criar listener.

### 9.28 Contrato de consumo seguro de `TransacaoCriada` — B04.1

**Estado:** decisão documental; nenhuma fila, binding, política de broker ou listener foi implementado neste incremento. O caso de uso de registro e a outbox de saída já são duráveis, mas isso ainda não autoriza afirmar que o fluxo de entrada está ativo.

| Fronteira | Contrato aprovado para a v1 |
|---|---|
| Origem | consumir somente a fila `credpay.processamento.transacao-criada.v1`, pertencente ao `processamento-service`, ligada à exchange do produtor `credpay.transacoes.v1` pela routing key `transacao.criada.v1` |
| Topologia | fila de entrada e DLQ quorum, duráveis, não exclusivas e não auto-delete; DLX direct `credpay.processamento.dlx.v1` com routing key `transacao.criada.dlq.v1`; o produtor não declara recursos do consumidor |
| Paralelismo inicial | um consumidor na v1, `prefetch=10`; a serialização por identidades no PostgreSQL continua sendo a defesa para reentregas/concorrência |
| Ack | `AcknowledgeMode.AUTO` do container Spring, que confirma a mensagem após o listener retornar com o commit do caso de uso concluído; nunca `autoAck`/no-ack do protocolo RabbitMQ |
| Transação | `RegistrarProcessamentoService` confirma resultado e intenção de saída na mesma transação PostgreSQL; não há transação distribuída com RabbitMQ |
| Reentrega | o mesmo `eventId` e conteúdo equivalente reutilizam resultado, política congelada e `outputEventId`, sem nova linha na outbox; divergência é conflito permanente |
| Retry | erro operacional transitório recebe no máximo três tentativas totais, incluindo a primeira, com esperas de 1 e 2 segundos; após esgotar, rejeitar sem requeue para DLQ; erro permanente vai direto à DLQ |
| Ativação | listener permanece desligado até topologia, validação, ack pós-commit, classificação de erros e DLQ passarem em RabbitMQ/PostgreSQL reais |

O adaptador de entrada validará **antes de decidir**: JSON objeto legível; `eventId` e `data.transactionId` como UUID; `eventType=TransacaoCriada`, `eventVersion=1`, `data.status=PENDENTE`; `occurredAt` como `Instant`; `data.amount` como texto decimal positivo sem conversão binária; `data.currency` como código ISO 4217; `correlationId=data.transactionId`; propriedades AMQP `messageId=eventId`, `type=TransacaoCriada` e `correlationId=data.transactionId`. Campos JSON adicionais compatíveis podem ser ignorados; ausência, tipo incorreto, versão desconhecida ou inconsistência de identidade são erros permanentes. O payload cru não será escrito em log. A política de moeda ausente no processador é erro operacional, nunca decisão financeira `REJEITADA`.

| Resultado do listener | Tratamento exigido |
|---|---|
| entrada válida nova | commit de resultado e intenção, então retorno normal e ack pelo container |
| reentrega equivalente | reler snapshot original; retorno normal e ack, sem nova decisão ou evento |
| JSON/contrato inválido, versão incompatível ou conflito de identidade | não decidir nem sobrescrever; rejeitar sem requeue e encaminhar à DLQ, com diagnóstico seguro |
| banco indisponível, timeout de infraestrutura ou ausência de política configurada para a moeda | não converter em estado financeiro; retry limitado e, se persistir, DLQ com motivo observável |
| queda depois do commit e antes do ack | reentrega idempotente encontra o registro durável; ack somente depois da nova execução terminar |

O mecanismo de retry do listener deve classificar falhas permanentes sem tentativas inúteis e impedir requeue infinito. A escolha/versão de uma eventual dependência Spring Retry será verificada e justificada no incremento que implementar esse mecanismo; **nenhuma dependência é adicionada agora**. Falha de conexão do container com o broker é recuperação de infraestrutura, não uma tentativa de processamento de uma mensagem. O teste verificará número de entregas/tentativas e destino final, sem depender apenas de mocks.

Para dead-lettering confiável, a fila quorum de origem não deve ser confundida com garantia automática: no RabbitMQ 4.3 o padrão é `at-most-once`. A v1 **almeja** `at-least-once` entre fila de entrada e DLQ por política do broker escopada à fila de origem, com `dead-letter-strategy=at-least-once`, `overflow=reject-publish`, `dead-letter-exchange` e routing key configurados, além da feature flag necessária. Antes de ligar o listener, teste real deve provar configuração efetiva, roteamento à DLQ, retenção na origem quando o destino não confirma e recuperação após restaurar o destino. Limite finito de comprimento/capacidade será escolhido e testado no incremento de topologia; até lá não há alegação de proteção contra crescimento ilimitado. Se o mecanismo não puder ser comprovado no ambiente local, registrar a limitação e não chamar a DLQ de at-least-once. Mesmo com essa estratégia, duplicatas na DLQ são possíveis e um nó único não prova alta disponibilidade. Referência: [RabbitMQ Quorum Queues](https://www.rabbitmq.com/docs/quorum-queues).

**Critérios de prova incremental:** (1) declarar topologia e política efetiva sem listener; (2) validar entrada correta e negativa sem decisão; (3) confirmar ack somente após commit e reentrega equivalente após queda; (4) demonstrar retry limitado, erro permanente direto à DLQ e falha do destino de dead-lettering; (5) manter `eventId` e intenção de saída únicos. Cada etapa terá seu próprio red/green quando introduzir comportamento, sem ativação antecipada do consumidor.

**Próximo:** implementar e testar somente a topologia/política de entrada e DLQ em RabbitMQ real, ainda sem listener.

### 9.29 Topologia de entrada e DLQ opt-in — B04.2

O `processamento-service` declara, somente com `credpay.processamento.consumer.topology.enabled=true`, a fila quorum durável `credpay.processamento.transacao-criada.v1`, seu binding com `credpay.transacoes.v1`/`transacao.criada.v1`, a DLX direct durável `credpay.processamento.dlx.v1` e a fila quorum durável `credpay.processamento.transacao-criada.dlq.v1` ligada por `transacao.criada.dlq.v1`. A exchange do produtor pertence ao `transacoes-service`: o teste a declara como fixture, não a configuração de produção do consumidor. O padrão é desligado; não há listener, ack, retry de processamento ou fluxo ponta a ponta.

A política RabbitMQ, escopada à fila quorum de entrada, define `dead-letter-strategy=at-least-once`, `overflow=reject-publish`, `max-length=10000`, DLX e routing key. A DLQ de teste recebe política própria `max-length=1` e `overflow=reject-publish` para simular destino cheio. As políticas são aplicadas no container do teste, **não** são provisionadas pela aplicação; implantação futura deverá instalá-las e verificar sua definição efetiva e a feature flag `stream_queue` antes de habilitar o consumidor. `max-length=10000` limita mensagens, não bytes nem tempo de retenção; a DLQ operacional ainda precisa de política de capacidade e procedimento de inspeção/replay.

**Evidência:** [CI #92](https://github.com/Joaomagh/credpay/actions/runs/37057606496) vermelho pela ausência da fila; [CI #106](https://github.com/Joaomagh/credpay/actions/runs/37062544845) verde com 79 testes, zero falhas/erros/skips. No RabbitMQ 4.3.5 real, os testes confirmaram declaração, política efetiva, roteamento pela exchange do produtor e entrega do mesmo payload à DLQ após o teste ocupar e liberar sua única vaga. Isso caracteriza o comportamento observado, mas não prova por si só que o broker tentou publicar enquanto a DLQ estava cheia. `test-compile` e 26 testes sem Docker passaram localmente; o Docker Desktop local não estava disponível. Houve correção de fixture para não reutilizar o contexto de outro teste. Os contadores imediatos de `rabbitmqctl list_queues` divergiram da observação por AMQP, por isso não foram usados como prova de retenção.

**Limite de prova:** a retenção durante a recusa efetiva da DLQ e a recuperação após remover/restaurar seu binding não ficaram demonstradas nos experimentos iniciais; não são alegadas como garantias deste incremento. Também não há prova de alta disponibilidade com broker de nó único. Antes de ativar listener, validar esses cenários e a política operacional; falha nessa verificação deve manter o consumo desativado. Esta etapa não introduz dependência nova.

No [CI #109](https://github.com/Joaomagh/credpay/actions/runs/37064110328), o teste leu a contagem da DLQ imediatamente após publicar o ocupante e encontrou `0`: uma corrida da fixture, não uma falha comprovada da topologia. A espera limitada pela condição observável corrigiu a fixture; o [CI #110](https://github.com/Joaomagh/credpay/actions/runs/37064493609) voltou a passar sem reduzir a asserção.

### 9.30 Validação isolada de `TransacaoCriada` v1 — B04.3

`TransacaoCriadaMessageParser` transforma uma `Message` AMQP em `TransacaoCriadaRecebida` sem acessar banco nem tomar decisão financeira. Exige objeto JSON legível, tipo `TransacaoCriada`, versão inteira `1`, UUIDs canônicos, `occurredAt` como instante, `data` objeto, `amount` textual interpretado por `BigDecimal`, moeda ISO 4217 e `status=PENDENTE`. O domínio rejeita valor não positivo e correlação JSON divergente da transação. O parser exige `messageId=eventId`, `type=TransacaoCriada` e `correlationId=data.transactionId` nos headers AMQP; campos JSON adicionais compatíveis são aceitos. Erros de parsing retornam `IllegalArgumentException` com nome do campo, sem incluir o valor recebido ou registrar o payload. A classe não é listener nem bean ativo.

**TDD:** red inicial por ausência do parser; novo red com duas coerções indevidas do Jackson (`eventVersion` textual e `amount` numérico), corrigidas com checagem de tipos; red de `data` ausente e UUID inválido que aparecia na mensagem de erro, corrigidos por validação/sanitização; red de UUID abreviado aceito pelo JDK, corrigido pela exigência da forma canônica. Teste focado verde com 20 cenários; 45 testes sem Docker verdes localmente; [CI #110](https://github.com/Joaomagh/credpay/actions/runs/37064493609) verde com 99 testes, zero falhas, erros ou skips. O warning Mockito/Byte Buddy conhecido permanece.

**Limites:** sem listener, ack, classificação de falhas, retry, DLQ operacional, limite de tamanho do payload ou prova de JSON com chaves duplicadas. Esses itens devem ser avaliados antes de ativar a entrada; nenhum contrato de pagamento real é inferido desta validação isolada. Nenhuma dependência foi adicionada.

**Próximo:** B04.4, provar retenção e recuperação do dead-lettering quando o destino recusa ou perde a rota, sem habilitar listener.

### 9.31 Retenção e recuperação da rota de dead-lettering — B04.4

Com política efetiva `at-least-once` na fila quorum de origem, o teste remove o binding DLX→DLQ, publica e rejeita uma mensagem sem requeue. Com a rota ainda ausente, verifica que a DLQ não recebeu o payload e que `rabbitmqctl list_queues name messages` mantém uma mensagem na origem. Após restaurar o binding, recebe o **mesmo payload** na DLQ. Isso exercita uma falha real de roteamento, sem listener da aplicação nem alteração do código de produção.

**Evidência:** [CI #113](https://github.com/Joaomagh/credpay/actions/runs/37065126334) e [CI #114](https://github.com/Joaomagh/credpay/actions/runs/37065508507) falharam porque a espera de 30 segundos não cobria a nova tentativa interna; o diagnóstico de #114 confirmou política efetiva e `messages=1` na origem. O [CI #115](https://github.com/Joaomagh/credpay/actions/runs/37066089787) passou com 100 testes, zero falhas/erros/skips, após ampliar a espera máxima para 210 segundos. A classe de topologia levou 190 segundos nessa execução. O [material oficial do RabbitMQ](https://www.rabbitmq.com/blog/2022/03/29/at-least-once-dead-lettering) descreve retry periódico com intervalo padrão de três minutos; o tempo observado no CI é compatível, mas não constitui SLA.

**Limites:** a prova cobre rota ausente/restaurada em broker de nó único, não recusa efetiva por DLQ cheia, falha de nó ou alta disponibilidade. A política foi aplicada apenas pela fixture Testcontainers; provisionamento e verificação operacionais continuam pendentes. O listener permanece desligado. O tempo de recuperação precisa ser considerado na futura operação/monitoramento da DLQ. Nenhuma dependência nova entrou.

No [CI #129](https://github.com/Joaomagh/credpay/actions/runs/37073249056), uma leitura única de `rabbitmqctl list_queues` ainda mostrou `messages=0` após a rejeição, antes de a estatística de retenção aparecer. A fixture agora aguarda até 15 segundos por `messages=1` com a rota ausente; não libera a rota se isso não for observado. O [CI #130](https://github.com/Joaomagh/credpay/actions/runs/37073573236) voltou a passar com 103 testes. A prova de retenção foi mantida, não removida.

**Próximo:** B04.5, provar o caminho válido do listener opt-in e ack somente após commit em RabbitMQ e PostgreSQL reais, sem habilitação por padrão.

### 9.32 Listener opt-in com ack após commit no caminho válido — B04.5

`TransacaoCriadaListener` só é criado quando `credpay.processamento.consumer.topology.enabled=true` **e** `credpay.processamento.consumer.listener.enabled=true`. Recebe uma mensagem da fila própria, usa o parser de B04.3 e chama `RegistrarProcessamentoService.executar`. Há um consumidor, `prefetch=10` e `AcknowledgeMode.AUTO`: o container confirma a entrega após o retorno do método, que ocorre depois do commit da transação do serviço. Os dois flags ficam ausentes/desligados na configuração padrão. Não há handler de erro, retry ou ativação operacional neste incremento.

**TDD e evidência:** [CI #118](https://github.com/Joaomagh/credpay/actions/runs/37067608989) falhou por vazamento da fixture para outro contexto e não conta como red de comportamento. Após isolá-la, [CI #119](https://github.com/Joaomagh/credpay/actions/runs/37067850746) foi o red válido: o contexto subiu e a pausa antes do commit nunca foi alcançada, pois não existia listener. No primeiro green, [CI #120](https://github.com/Joaomagh/credpay/actions/runs/37068431322) chegou à pausa, mas a leitura imediata da estatística de entregas sem ack ainda mostrava zero. Uma espera limitada preservou a asserção; [CI #121](https://github.com/Joaomagh/credpay/actions/runs/37069062896) passou com 101 testes, zero falhas/erros/skips. Durante a pausa após inserir a outbox, outra conexão não via resultado nem intenção e o broker mostrava uma entrega sem ack. Após liberar a transação, ambos ficaram duráveis e a fila voltou a zero. `test-compile` e `git diff --check` passaram localmente; Docker Desktop local indisponível.

**Limites:** a prova é do caminho válido em PostgreSQL/RabbitMQ de teste. Não prova replay pelo listener, crash entre commit e ack, erro permanente para DLQ, retry finito ou política de broker provisionada fora da fixture. O listener não deve ser habilitado em execução normal antes dessas proteções; sem tratamento, exceções do listener podem causar reentrega ilimitada. Nenhuma dependência foi adicionada.

**Próximo:** B04.6, provar replay equivalente do mesmo evento pelo listener sem duplicar resultado nem intenção de saída.

### 9.33 Replay equivalente pelo listener — B04.6

O teste de integração envia duas vezes o mesmo `TransacaoCriada` à exchange do produtor com a fila de entrada ligada. Um observador de teste confirma a segunda busca pelo `eventId` no caso de uso e a pausa antes do retorno deixa a segunda entrega sem ack. Com PostgreSQL real, há uma única linha em `processamentos`, uma única intenção na outbox e o mesmo `outputEventId` da primeira decisão. Após liberar o retorno, a fila volta a zero. Não há mudança de código de produção.

**Evidência:** o teste nasceu verde porque a idempotência já estava implementada no caso de uso; é caracterização do caminho RabbitMQ → listener → aplicação, não um ciclo red/green fabricado. O [CI #124](https://github.com/Joaomagh/credpay/actions/runs/37070642793) executou 102 testes, zero falhas/erros/skips, em PostgreSQL e RabbitMQ reais. `test-compile` e `git diff --check` passaram localmente; Docker Desktop local continua indisponível.

**Limites:** duas publicações equivalentes não simulam uma queda entre commit e ack, nem provam retries transitórios ou tratamento de payload inválido. A política do broker e o listener operacional seguem pendentes. Nenhuma dependência foi adicionada.

**Próximo:** B04.7, rejeitar mensagem inválida diretamente para DLQ com motivo verificável, sem nova decisão e sem requeue inútil.

### 9.34 JSON inválido rejeitado diretamente para DLQ — B04.7

O listener captura apenas `IllegalArgumentException` do parser de entrada e lança `AmqpRejectAndDontRequeueException` com mensagem de campo/contrato, sem incluir corpo ou valores recebidos. O container rejeita a entrega sem requeue; a política `at-least-once` da fila quorum encaminha para a DLQ. Exceções do caso de uso ou do banco não são capturadas neste incremento.

**TDD e evidência:** o teste novo fixa uma política efetiva no RabbitMQ descartável, publica JSON malformado, exige `x-first-death-reason=rejected` na DLQ e ausência de novas linhas de resultado/outbox. O [CI #127](https://github.com/Joaomagh/credpay/actions/runs/37071897057) falhou antes da correção, mas o requeue repetido produziu cerca de 86 MB de logs e impediu recuperar a linha final da asserção pelo conector; isso é uma limitação da evidência red, não um green omitido. O [CI #128](https://github.com/Joaomagh/credpay/actions/runs/37072605070) passou e mostrou uma rejeição com diagnóstico `TransacaoCriada invalida: JSON inválido`. Após a correção da fixture de retenção não relacionada, o [CI #130](https://github.com/Joaomagh/credpay/actions/runs/37073573236) confirmou 103 testes, zero falhas/erros/skips. `test-compile` e `git diff --check` passaram localmente; Docker Desktop local indisponível. O teste não aceita `delivery_limit` como substituto de rejeição direta.

**Limites:** só erros emitidos pelo parser são classificados como permanentes. Conflito de identidade do caso de uso, indisponibilidade de banco e falhas sem classificação continuam sem retry seguro; por isso o listener segue desligado por padrão. Não há política operacional provisionada fora da fixture nem prova de payload sensível em todos os caminhos de log. Nenhuma dependência foi adicionada.

**Próximo:** B04.8, classificar conflito de identidade como permanente e provar DLQ sem sobrescrever o resultado original, com teste red limitado para evitar explosão de logs.

### 9.35 Conflito de identidade rejeitado sem requeue — B04.8

O listener captura somente `ConflitoProcessamentoException` da chamada transacional e a converte em `AmqpRejectAndDontRequeueException` com mensagem fixa `TransacaoCriada com conflito de identidade`, sem anexar causa ou mensagem interna. A falha permanente não muda o resultado financeiro original. Falhas operacionais não são capturadas por essa classificação e continuam pendentes de retry seguro; o listener permanece opt-in.

**TDD local:** `mvnw.cmd -Dtest=TransacaoCriadaListenerTest test` executou um teste com uma falha esperada: recebeu `ConflitoProcessamentoException` em vez da rejeição sem requeue. Após implementação mínima, o comando focado com `TransacaoCriadaListenerTest,TransacaoCriadaMessageParserTest,RegistrarProcessamentoServiceTest` passou com 31 testes, zero falhas/erros/skips. O segundo teste do listener é regressão de caracterização: indisponibilidade de banco preserva a exceção operacional, sem red artificial. Houve um erro de compilação acidental na asserção de `Map<?, ?>` do header e um comando PowerShell com lista não delimitada; ambos corrigidos, não contados como red. Warning Mockito/Byte Buddy permanece.

**Integração comprovada:** o [CI #133](https://github.com/Joaomagh/credpay/actions/runs/37095277698) executou 106 testes, zero falhas/erros/skips, em 4 min 35 s. O cenário publica entrada válida e espera resultado/outbox e fila vazia; publica as mesmas identidades com valor divergente válido; exige segunda mensagem na DLQ com corpo/messageId preservados, origem correta e motivo `rejected`. Compara todas as colunas de resultado/outbox antes/depois e exige uma única linha de cada agregado. `x-death.count=1` mede dead-letterings, não tentativas do listener; não é prova isolada contra requeue. Política efetiva configurada na fixture, sem depender da ordem dos testes. Docker Desktop local continua sem engine disponível; `verify` local limitado às classes sem infraestrutura passou com 71 testes e JAR gerado, não substituindo o CI completo.

**Revisão:** outro agente fez revisão somente leitura, sem achado bloqueante; destacou a semântica limitada de `x-death.count`. Nenhuma dependência, POM, endpoint ou contrato de evento alterado. Retry transitório, crash entre commit/ack e política operacional permanecem fora deste slice.

**Próximo:** B04.9, provar retry operacional com no máximo três tentativas totais e intervalos de 1 e 2 segundos, recuperação ou DLQ ao esgotar, sem repetir erros permanentes. `dependency:tree` offline confirmou Spring Retry 2.0.13 já transitivo de Spring AMQP 3.2.12; não há necessidade identificada de dependência nova.

### 9.36 Retry limitado no consumidor opt-in — B04.9

**Baseline:** Spring Retry 2.0.13 já transitivo de Spring AMQP 3.2.12, confirmado por `dependency:tree` offline. A factory nomeada usa primeiro `SimpleRabbitListenerContainerFactoryConfigurer` do Boot e depois adiciona advice stateless; preserva prefetch e configuração do container. Factory/interceptor só existem com ambas as flags de topologia/listener habilitadas. Nenhuma dependência ou POM novo.

`SimpleRetryPolicy` limita a três tentativas totais e percorre causas para não repetir `AmqpRejectAndDontRequeueException`. Todas as outras exceções, inclusive desconhecidas ou ausência de política por moeda, são retryáveis até esse limite; não é uma allowlist que prove que toda falha é transitória. `ExponentialBackOffPolicy` configura intervalo inicial de 1000 ms, multiplicador 2 e máximo de 2000 ms. O advice envolve o listener; cada chamada ao serviço abre sua própria transação, sem transação externa que englobe todas as tentativas.

O recoverer preserva a rejeição permanente segura encontrada na cadeia; no esgotamento lança rejeição sem requeue, mensagem fixa `TransacaoCriada com falha operacional apos 3 tentativas`, sem causa nem logging do `Message`. Isso não converte falha operacional em resultado financeiro `REJEITADA`/`FALHOU`, nem garante sanitização de todos os logs da infraestrutura.

**TDD:** antes da implementação, quatro testes executaram com três falhas esperadas: não recuperou após falha inicial, não rejeitou seguramente ao esgotar e não desembrulhou a rejeição permanente. O cenário default-off já passava e é caracterização. Após a implementação, a fixture com um único argumento falhou no recoverer AMQP; foi corrigida para a assinatura canal/mensagem do container, sem alterar a produção para acomodá-la. Quatro testes verdes e `verify` sem classes de infraestrutura com 75 testes/JAR. A duração observada de pelo menos três segundos prova espera total mínima; os intervalos individuais são configuração inspecionada, não medidos separadamente. Mockito/Byte Buddy continua com warning conhecido.

**Integração comprovada:** o [CI #136](https://github.com/Joaomagh/credpay/actions/runs/37096399603) executou 112 testes, zero falhas/erros/skips, em 4 min 57 s; os seis cenários do listener passaram. Decorator da outbox faz INSERT real e lança falha controlada após ele. Na recuperação, pausa a segunda tentativa antes do INSERT, lê resultado/outbox por outra conexão e exige ausência; depois libera, exige exatamente duas chamadas e um único commit. No esgotamento, exige três chamadas, nenhuma linha das novas identidades e mensagem original na DLQ com `rejected`. São falhas controladas na aplicação com banco real, não queda real de PostgreSQL. Docker local indisponível; não há green local de integração alegado. A busca limitada de formatos comuns de segredo/payload bruto no log não encontrou candidatos; o diagnóstico observado no esgotamento foi fixo e sem causa interna.

**Revisão:** agente revisor não encontrou bloqueante; foram reforçadas as verificações de ausência da factory nas combinações incompletas de flags. Crash entre commit/ack, provisionamento de política operacional e ativação normal seguem pendentes.

**Próximo:** B04.10, fechar deliberadamente a conexão depois do commit e antes do ack; exigir `redelivered=true`, mesmo corpo/identidade e snapshot/outbox intactos na reentrega real. Não confundir retry in-process com reentrega pelo broker nem alegar process kill.

### 9.37 Reentrega real na janela commit/ack — B04.10

**Experimento comprovado:** teste exclusivo da fixture instala advice externo ao retry real. Ele chama o listener, espera o retorno normal do caso de uso (commit concluído) e pausa antes de devolver ao container AUTO. Outra conexão lê resultado/outbox completos e o broker mostra uma mensagem sem ack. `CachingConnectionFactory.resetConnection()` fecha a conexão AMQP compartilhada; a barreira é liberada e o teste exige segunda entrega concluída normalmente, `redelivered=true`, mesmo corpo/messageId, todas as colunas preservadas, uma linha de cada tabela, fila vazia e nenhuma mensagem na DLQ.

O teste confirma o container/fila-alvo, usa `shutdown()` para reconstruir o proxy de advice no `start()` e restaura o retry original em `finally`, liberando a barreira antes da limpeza. APIs conferidas no código oficial [container](https://github.com/spring-projects/spring-amqp/blob/v3.2.12/spring-rabbit/src/main/java/org/springframework/amqp/rabbit/listener/AbstractMessageListenerContainer.java) e [connection factory](https://github.com/spring-projects/spring-amqp/blob/v3.2.12/spring-rabbit/src/main/java/org/springframework/amqp/rabbit/connection/CachingConnectionFactory.java), versão 3.2.12 do projeto. Não envolve a execução em transação externa nem republica o evento para imitar reentrega.

**Evidência/revisão:** [CI #140](https://github.com/Joaomagh/credpay/actions/runs/37098555364) passou com 113 testes, zero falhas/erros/skips, em 5 min 15 s; os sete cenários do listener passaram. [Secret Scan #5](https://github.com/Joaomagh/credpay/actions/runs/37098555357) também verde. A caracterização nasceu verde com produção existente; nenhum red artificial ou alteração de produção/POM/dependência. Outro agente revisou fixture, ordem do advice, reentrega versus retry e cleanup, sem bloqueante. `test-compile` local passou; `mvnw.cmd --batch-mode --no-transfer-progress '-Dtest=!**/*IntegrationTest,!ProcessamentoServiceApplicationTest' verify` passou com 75 testes e JAR. Docker Desktop local segue sem pipe do engine; não há integração local alegada. A busca limitada de formatos comuns de segredo e formas de payload bruto no log do job não encontrou candidatos.

**Limites:** fechamento de conexão não é process kill, falha de nó ou prova de HA. Consumidor operacional continua desativado. Warnings conhecidos de Mockito/Byte Buddy permanecem. O CI registrou 39 warnings Hikari de conexões PostgreSQL já fechadas; diagnóstico do ciclo de vida das fixtures/contextos registrado em B08.2, sem silenciar logs ou afirmar causa não comprovada.

**Próximo:** B04.11, comprovar a tentativa de dead-lettering efetivamente recusada pela DLQ cheia, retenção e recuperação depois de liberar capacidade. Depois, provisionamento mínimo da política e avanço a B05; falha de nó/carga ficam em B06, não expandem indefinidamente B04.

### 9.38 Recusa efetiva da DLQ cheia — B04.11

**Experimento comprovado:** o cenário antigo liberava capacidade imediatamente após rejeitar a entrada, antes de provar tentativa de publicação do worker. Foi fortalecido para manter dois ocupantes na DLQ de `max-length=1`: o [FIFO da versão 4.3.5](https://github.com/rabbitmq/rabbitmq-server/blob/v4.3.5/deps/rabbit/src/rabbit_fifo.erl) considera excesso com `>`, permitindo overshoot; a contagem não é um limite estrito nem prova isolada de recusa.

Depois de rejeitar um único evento com `messageId`, o teste usa `rabbitmqctl eval` somente de leitura no broker descartável. Consulta `sys:get_status` dos workers do [supervisor](https://github.com/rabbitmq/rabbitmq-server/blob/v4.3.5/deps/rabbit/src/rabbit_fifo_dlx_sup.erl), seleciona a fila de origem e exige `publish_count >= 1` com a DLQ em `rejected`, conforme o [worker](https://github.com/rabbitmq/rabbitmq-server/blob/v4.3.5/deps/rabbit/src/rabbit_fifo_dlx_worker.erl). A [fila quorum](https://github.com/rabbitmq/rabbitmq-server/blob/v4.3.5/deps/rabbit/src/rabbit_quorum_queue.erl) produz a rejeição por limite; não é um nack de publicação diagnóstica separada.

Só após observar a recusa, retenção na origem e dois ocupantes ainda na DLQ, o teste drena os ocupantes e espera o mesmo corpo/messageId, motivo `rejected` e origem vazia. Timeout de até 210 segundos respeita a recuperação do broker, sem espera fixa apresentada como evidência. `format_status` omite o corpo das entregas; a expressão projeta apenas booleano/estados fixos. Não imprimir estado completo, stderr diagnóstico ou conteúdo inesperado em asserções. Esquema incompatível falha explicitamente.

**Precisão da evidência:** o worker conserva a rejeição e o destino, mas descarta o código do motivo. A atribuição à capacidade vem da política/ocupação e do cenário controlado, apoiada no código da fila quorum; o teste não lê diretamente `maxlen`. Revisão assistida verificou essa distinção, filtro da origem, segurança da projeção e cleanup, sem bloqueante.

**Validação:** [CI #143](https://github.com/Joaomagh/credpay/actions/runs/37099496150) passou com 113 testes, zero falhas/erros/skips, em 8 min 6 s. Os quatro cenários de topologia passaram, incluindo recusa efetiva, retenção e recuperação, em 370,7 segundos; o aumento reflete duas esperas de recuperação do broker, não sleep fixo usado como prova. [Secret Scan #8](https://github.com/Joaomagh/credpay/actions/runs/37099496135) verde. `test-compile` local e UTF-8/diff-check passaram; Docker local indisponível, sem integração local alegada. Caracterização nasceu verde, sem red artificial ou produção/POM/dependência nova. Busca limitada de formatos de segredo/payload bruto no log sem candidatos; 38 warnings Hikari de conexão fechada continuam em B08.2.

**Limites:** diagnóstico interno acoplado à versão/digest fixados, exclusivo da fixture, não API operacional da aplicação. Um único evento permite atribuir a pendência observada; não prova HA, ausência de duplicatas ou throughput. Cleanup remove somente as duas filas do broker descartável, incluindo o worker/pendências da origem, e o próximo `BeforeEach` as recria. Consumo operacional segue desligado até provisionamento/verificação da política. Foram corrigidos os resumos vigentes em 9.3/9.12 que ainda tratavam retry/reentrega já comprovados como futuros, sem reescrever a evidência histórica de cada incremento.

**Próximo:** B04.12, versionar e validar o provisionamento mínimo das políticas, com capacidade finita, feature flag/precondições e verificação da definição efetiva antes de habilitar consumo. Sem cluster, tuning, dashboard ou implantação externa; então B05 para fechar o fluxo consultável.

### 9.39 Provisionamento mínimo das políticas — B04.12

**Decisão:** `infra/rabbitmq/processamento-policies.json` importa somente as duas políticas no vhost `/`, com nomes estáveis, prioridade `10`, padrões exatos e `apply-to=quorum_queues`. Entrada mantém `max-length=10000`, `dead-letter-strategy=at-least-once`, `overflow=reject-publish`, DLX e rota v1; DLQ recebe `max-length=1000` e `reject-publish`. A baseline da DLQ é didática, menor para tornar acúmulo visível, sem dimensionamento de produção. Não há TTL/drop-head, teto de bytes ou limite de armazenamento das outboxes. Quorum permite overshoot; quantidade configurada não é teto estrito.

**Preparação:** o [runbook](infra/rabbitmq/README.md) declara a exchange pelo produtor e a topologia pelo processador com listener/publicadores desligados, copia/importa o artefato e confere as duas filas individualmente: tipo/durabilidade, argumentos, política selecionada, operator policy, definição efetiva, bindings e `stream_queue=enabled`. Qualquer divergência mantém listener desligado; só depois habilitar manualmente uma instância. Não é bloqueio automático de startup nem defesa contra mudança posterior. Outro vhost exige adaptação e validação próprias. Referências verificadas: [importação](https://www.rabbitmq.com/docs/definitions), [CLI](https://www.rabbitmq.com/docs/man/rabbitmqctl.8) e [pré-condições/limites quorum](https://www.rabbitmq.com/docs/quorum-queues).

**Prova implementada, posteriormente validada no CI abaixo:** `RabbitMqPoliticasOperacionaisIntegrationTest` copia o mesmo artefato versionado para RabbitMQ descartável fixado no digest existente, importa/reimporta, compara JSON efetivo por nome exato, confere flag/argumentos/política/ausência de consumidores e preserva sentinelas nas duas filas. Aguarda `messages_ready=1` em ambas antes de reaplicar, conforme achado da revisão assistida; sem essa espera poderia provar somente entrega posterior. Uma fila quorum fora dos padrões não recebe as políticas. Não usa cópia Java do arquivo para importar; valores esperados são assertions independentes. O filtro do CI inclui `infra/rabbitmq/**` em PR/push. Sem dependência, listener ou contrato de negócio novo; configuração operacional não recebe red artificial.

**Verificação local:** `mvnw.cmd --batch-mode --no-transfer-progress -o test-compile` passou; `mvnw.cmd --batch-mode --no-transfer-progress -o '-Dtest=!**/*IntegrationTest,!ProcessamentoServiceApplicationTest' verify` passou com 75 testes, zero falhas/erros/skips e JAR. A tentativa focada de integração executou 1 teste com erro de ambiente `Could not find a valid Docker environment`; não chegou ao broker. `docker desktop start --timeout 45` expirou e o pipe Linux continuou ausente. Não houve reset, prune, montagem de Docker socket ou redução de teste. Logs locais ignorados em `.local/b04-12-*.log`; warning Mockito/Byte Buddy permanece. Naquele momento, aceite do broker e suíte completa dependiam do CI antes do merge; o resultado posterior está abaixo.

**Falha de preparação no CI:** o [CI #146](https://github.com/Joaomagh/credpay/actions/runs/37135071561) executou 114 testes, com uma falha no teste novo e os 113 anteriores verdes. Importação, feature flag e tipo/política da primeira fila chegaram às assertions; a conferência de argumentos esperava objeto JSON, mas a CLI retornou `[["x-queue-type","longstr","quorum"]]`, tabela AMQP tipada. Teste e runbook foram corrigidos para a representação observada, mantendo comparação exata e rejeição de argumentos extras. Não é red de negócio nem prova completa da reaplicação. O [código quorum 4.3.5](https://github.com/rabbitmq/rabbitmq-server/blob/v4.3.5/deps/rabbit/src/rabbit_quorum_queue.erl) retorna diretamente os argumentos AMQP em `i(arguments, Q)`. A regressão completa foi exigida novamente sem skip ou alteração de produção; o resultado posterior está abaixo.

**Aceite e integração:** o [CI #147](https://github.com/Joaomagh/credpay/actions/runs/37135722088), SHA `f8f61b0`, passou com 114 testes, zero falhas/erros/skips, em 8 min 26 s. O novo teste passou em 12,73 s, comprovando importação, políticas efetivas, flag, escopo, ausência de consumidores e preservação das sentinelas residentes na reaplicação. [Secret Scan #12](https://github.com/Joaomagh/credpay/actions/runs/37135722077) verde no mesmo SHA; revisão assistida sem bloqueante após os dois ajustes da fixture. [PR #93](https://github.com/Joaomagh/credpay/pull/93) integrada em `ef31015`, mantendo os commits revisados e seus fingerprints; `main` sincronizada. O log registrou 40 warnings Hikari de conexão fechada, ainda em B08.2. A busca limitada de formas `body`/`failedMessage` com `amount` não encontrou candidatos; não é auditoria completa de logs. B04.12 conclui o provisionamento mínimo; não ativa automaticamente o consumidor nem prova HA/fluxo completo.

### 9.40 Contrato de aplicação de TransacaoProcessada — B05.1

**Decisão documental:** o `transacoes-service` aplicará o resultado da seção 6 em seu próprio banco, sem acessar dados do processador. Esta seção define os próximos testes; não implementa estados finais, histórico, parser ou listener. P.O. e revisão sênior confirmaram o recorte dentro da v1.

| Entrada | Resultado exigido |
|---|---|
| Primeiro evento válido para transação PENDENTE e causa conhecida | aplicar APROVADA ou REJEITADA, preservando ID/valor/escala/moeda; gravar transição real e recebimento no mesmo commit |
| Mesmo eventId e conteúdo conhecido equivalente | retornar a transição registrada, sem atualizar estado/instante ou criar outro histórico |
| Mesmo eventId divergente | conflito permanente; preservar histórico e estado originais |
| Outro eventId para transação já concluída | conflito permanente, mesmo que informe o mesmo status; sem alias/reprocessamento na v1 |
| Causa incorreta, transação desconhecida ou sem evento de criação correspondente | rejeição explícita, sem criar transação nem fabricar histórico; futuro listener envia para DLQ |
| Estado final existente | não retornar a PENDENTE nem substituir o estado final |
| Falha ao escrever status ou histórico | rollback integral; nenhum ack de sucesso |
| Duas entregas simultâneas equivalentes | uma transição durável; segunda observa replay após resolver disputa |
| Duas entregas simultâneas divergentes com identidade compartilhada | somente uma vence; a outra recebe conflito e preserva status/histórico completos da vencedora |

**Identidade e validação:** envelope objeto, `eventType=TransacaoProcessada`, `eventVersion=1`, `eventId`, `transactionId`, `correlationId` e `causationId` como UUIDs canônicos, `correlationId=transactionId`, `occurredAt` como `Instant` e somente status APROVADA/REJEITADA. AMQP exige `messageId=eventId`, `type=TransacaoProcessada` e correlação igual à transação. Campos extras compatíveis não participam da equivalência. Equivalência compara campos conhecidos, inclusive causa, instante e status, sem comparar JSON bruto. Embora o produtor gere micros, a leitura não truncará o instante recebido: epoch second/nano preservam a semântica, incluindo representações textuais do mesmo instante. Não introduzir valor/moeda no resultado.

**Causalidade:** `causationId` deve identificar o `TransacaoCriada` v1 da outbox local com `aggregate_id=transactionId`. Não exigir `published_at`: o retorno pode chegar na janela entre publisher confirm e marcação da outbox. Registros preparatórios sem evento de criação não são migrados por invenção de causa. A outbox de criação permanece disponível na v1; retenção futura deverá respeitar esse vínculo.

**Atomicidade e auditoria:** uma linha de histórico pode representar também o recebimento aplicado, com unicidade por eventId e transactionId, sem uma inbox redundante. Deve conservar identidade semântica completa, estado anterior PENDENTE, novo estado final, origem fixa `processamento-service/TransacaoProcessada.v1`, eventId/causationId, instante do evento com precisão preservada e instante local de aplicação. Só registrar a primeira transição real; não fabricar instante de criação para dados antigos. Status e histórico são confirmados numa única transação PostgreSQL, com constraints como defesa final e serialização concorrente comprovada. Ack somente após commit; reentrega pós-commit deve ler esse registro. Falha operacional não vira REJEITADA/FALHOU.

**Compatibilidade HTTP:** GET retorna o estado persistido atual. POST com chave/payload equivalentes continua repetindo a resposta da criação com PENDENTE, mesmo após o resultado final; isso muda apenas a representação de criação, sem reiniciar o registro. ID, valor/escala e moeda originais são imutáveis, portanto não se justifica snapshot HTTP adicional. Teste HTTP deverá exigir simultaneamente replay original, GET final e histórico inalterado.

**Sequência:** B05.2 domínio imutável e reconstrução de estado final; B05.3 persistência de transição/recebimento e rollback; B05.4 aplicação idempotente, causa e concorrência; B05.5 entrada opt-in e fluxo POST → eventos → GET. Cada comportamento seguirá TDD, sem introduzir listener antes da proteção durável. Reutilizar aprendizados de B04 sem repetir experimentos sem risco novo. FALHOU, replay operacional da DLQ, compensação e histórico inicial inventado ficam fora desta entrega.

### 9.41 Domínio final imutável e reconstrução — B05.2

**Implementação:** `StatusTransacao` admite PENDENTE, APROVADA e REJEITADA. `Transacao.concluir` devolve nova instância com os mesmos ID, valor/escala e moeda, aceitando explicitamente somente APROVADA/REJEITADA quando o objeto atual está PENDENTE. Estado final recusa qualquer segunda transição, inclusive para o mesmo estado ou PENDENTE; replay pertence à aplicação e deverá retornar antes de chamar o domínio. Resultado nulo/PENDENTE é inválido. O objeto original permanece intacto, sem setter, relógio, dependência ou geração de identidade.

**Reconstrução:** o mapper JPA usa switch exaustivo e conserva o estado armazenado. Reutiliza a fábrica validada e a conclusão pura em memória para estados finais; isso não gera histórico nem chama atualização de banco. Três testes unitários observam round-trip do mapper para os três estados. Duas fixtures PostgreSQL inserem snapshot final e o releem em outra transação depois de commit, com Flyway/mapeamento reais; o aceite desses cenários aguarda CI. Não há migration ou caso de uso para atualizar registro previamente PENDENTE neste slice.

**TDD local:** primeiro teste falhou na compilação somente pela ausência de `concluir`; implementação mínima e enum ampliado deram 12 testes verdes. O próximo red executou 18 testes com seis falhas esperadas por nova transição de estado final sem exceção; a guarda de estado deu 18 verdes. O terceiro red executou 20 testes com duas falhas esperadas por resultado nulo/PENDENTE sem exceção; a guarda de resultado deu 20 verdes, mais três testes de caracterização do mapper. Não houve mocks do domínio ou red por Docker indisponível.

**Refatoração e verificação:** resultado usa allowlist explícita dos dois enums e o mapper compartilha a reconstrução dos dados antes do switch. `mvnw.cmd --batch-mode --no-transfer-progress -o '-Dtest=TransacaoTest,TransacaoEntityTest' test` passou com 23 casos; `mvnw.cmd --batch-mode --no-transfer-progress -o '-Dtest=!**/*IntegrationTest,!TransacaoHttpTest,!PostgresRuntimeTest' verify` passou com 50 testes e JAR, incluindo repetição após refatoração. `test-compile` passou para toda a suíte. Revisão assistida sem bloqueante; diff/UTF-8 e CI completo serão exigidos antes de integrar. Docker local segue indisponível; warnings conhecidos de Mockito/Byte Buddy continuam. Logs red/green/refactor locais em `.local/b05-2-*.log`, ignorados.

**Limites:** domínio final não aplica evento nem habilita listener. A compatibilidade POST replay original versus GET final está definida em B05.1 e será implementada/testada junto da aplicação; os caminhos atuais continuam criando somente PENDENTE. Próximo: persistência atômica de status/histórico/recebimento, unicidade, precisão e rollback em B05.3.

**Aceite remoto:** o [Transaction Service CI #110](https://github.com/Joaomagh/credpay/actions/runs/37137259753), SHA `eadf5ee`, passou com 108 testes, zero falhas/erros/skips, em 1 min 3 s. Os 15 cenários do repository incluíram as duas releituras finais em PostgreSQL; os 20 de domínio e três de mapper também passaram. [Secret Scan #17](https://github.com/Joaomagh/credpay/actions/runs/37137259708) verde. Revisão assistida sem bloqueante, com limite explícito da fixture: prova snapshot inserido, não atualização de transação anteriormente PENDENTE. Log desse job sem warnings Hikari de conexão fechada; isso não resolve o diagnóstico pendente do processador em B08.2. Busca limitada de formas de payload bruto sem candidatos. O SHA documental final da [PR #95](https://github.com/Joaomagh/credpay/pull/95) deve permanecer verde antes do merge.

### 9.42 Persistência atômica de estado e histórico — B05.3

**Desenho em execução:** `TransicaoRepository` registra a transição e permite leitura por evento; `TransicaoRecebida` conserva todos os campos semânticos do envelope, estados, origem e instante local. Adapter JDBC transacional atualizará somente status de PENDENTE e inserirá histórico; nenhuma escrita isolada ou listener. Migration V5 terá PK de evento, unicidade por transação, FK da transação e FK composta causa/transação à outbox local; tipo/versão da criação serão conferidos no adapter. Não exigir published_at nem inventar histórico antigo. Instante recebido usa epoch second/nano; instante local será truncado a micros antes da escrita, precisão suportada por TIMESTAMPTZ.

**TDD inicial:** teste exige commit/releitura completa com PostgreSQL real, preservação monetária e nanos não múltiplos de micros. Red local somente pela ausência deliberada de porta/record; foram adicionados os tipos mínimos, sem adapter/migration. Compilação e red de integração serão registrados separadamente; Docker indisponível não conta como red. CI aplicável deve observar o comportamento ausente antes de implementar. Revisão assistida de desenho confirmou risco de FK isolada e necessidade de falha real na segunda escrita.

**Red remoto observado:** [CI #113](https://github.com/Joaomagh/credpay/actions/runs/37147563843), SHA `30f851f`, executou 109 testes: 108 anteriores passaram, novo cenário teve um erro pela ausência de bean `TransicaoRepository`. PostgreSQL/Flyway reais estavam disponíveis. Secret Scan #20 passou. Depois deste red, adapter transacional e primeira V5 foram escritos para o round-trip; constraints adicionais serão testadas no próximo ciclo antes de completar esta migration ainda não integrada.

**Primeiro green e red estrutural:** [CI #114](https://github.com/Joaomagh/credpay/actions/runs/37147748585), SHA `35a3118`, passou com 109 testes, sem falha/erro/skip. Novos testes diretos do banco exigiram unicidade, FKs e checks. [CI #115](https://github.com/Joaomagh/credpay/actions/runs/37147937667), SHA `6678a5f`, executou 123 testes com 12 falhas esperadas: operações inválidas não lançavam exceção. Os dois estados finais e a colisão de PK na segunda escrita passaram; esta última já comprovou rollback real do UPDATE, preservando histórico original e transação alvo PENDENTE. V5 foi então completada com as constraints; ela ainda não foi integrada/aplicada em ambiente permanente, portanto não houve edição de migration já entregue.

**Green estrutural e falha descoberta:** [CI #116](https://github.com/Joaomagh/credpay/actions/runs/37148080498), SHA `40a5bfc`, passou com 123 testes, inclusive os 15 do adapter. Foram acrescentados cenários de recusa ausente/final/causa tipo-versão e truncamento somente local. [CI #117](https://github.com/Joaomagh/credpay/actions/runs/37148217904), SHA `f9bc693`, executou 129 testes com cinco falhas: esperava-se IllegalStateException, observou-se InvalidDataAccessApiUsageException com a mesma mensagem, traduzida por EntityManagerFactoryUtils/HibernateJpaDialect no proxy @Repository. A falha não foi ignorada nem apresentada como red planejado. Experimento mínimo: exigir tipo próprio `TransicaoRecusadaException`, confirmar API ausente localmente e lançar RuntimeException específica na guarda, distinguindo recusa de falha técnica; aceite depende de nova execução real. Contexto desta fixture fecha após a classe; não resolve globalmente B08.2.

**Aceite:** [CI #118](https://github.com/Joaomagh/credpay/actions/runs/37148470023), SHA `019f04d`, passou com 129 testes, zero falhas/erros/skips, em 52 s. Os 21 do adapter comprovaram recusa tipada através do proxy, constraints, os dois finais, epoch/nano exatos, micros somente locais e rollback integral após colisão da segunda escrita. Secret Scan #25 verde. `test-compile` e verify offline sem infraestrutura passaram (50 testes e JAR); warnings Mockito/Byte Buddy conhecidos permanecem. Diff e UTF-8 estrito válidos; revisão assistida sem bloqueante. Log #118 sem warning Hikari de conexão fechada e busca limitada de formas body/failedMessage com amount sem candidatos; não é auditoria global. Logs locais em `.local/b05-3-*.log`, ignorados. Nenhuma dependência nova/migration antiga editada/backfill; outbox de criação não pode ser removida enquanto referenciada pelo histórico. PR #96 exige CI e scanner no SHA documental final antes do merge.

**Aprendizado e próximo:** atualização de status e inserção de histórico compartilham uma conexão/commit do banco próprio; a PK recusando a segunda escrita reverte o UPDATE. Isso protege atomicidade, mas não implementa replay ou serialização concorrente. B05.4 foi refinado em aplicação sequencial/causa (a), concorrência (b) e representação HTTP original (c). A aplicação retornará a transição sem reler a entidade JPA carregada antes do UPDATE JDBC; instante local virá somente de Clock. Listener continua ausente.

### 9.43 Aplicação sequencial idempotente do resultado — B05.4a

**Implementação em validação:** entrada tipada `TransacaoProcessadaRecebida` contém somente identidade, instante recebido, correlação, causa e estado final; valida nulos, correlação e allowlist de estados. Tipo/versão/origem são constantes do contrato v1, sem instante local externo. `AplicarResultadoService` transacional READ_COMMITTED lê primeiro o histórico: replay semanticamente equivalente devolve o original antes de consulta causal/relógio/escrita; divergência lança ConflitoResultadoException. Evento novo exige transação PENDENTE e causa TransacaoCriada v1 local, sem published_at; conclui domínio, usa Clock em micros e registra. Desconhecido/causa inválida são recusas tipadas; outro evento para estado final é conflito. Sem listener ou lock concorrente neste slice; erro SQL não é indiscriminadamente convertido em conflito.

**TDD local:** red de API ausente; primeira aplicação deu dois greens. Replay teve dois erros esperados porque ainda seguia o caminho de nova aplicação; green com quatro casos. Conflito teve seis falhas reais (quatro divergências aceitas e duas exceções genéricas para estado final); green com dez. Construção inválida teve oito falhas esperadas por nenhuma exceção; validação deu 18 casos focados verdes. Mais duas caracterizações de desconhecido/causa ausente nasceram verdes. Equivalência foi extraída do caso de uso para TransicaoRecebida.correspondeA; verify offline pós-refatoração passou com 70 testes e JAR, warnings Mockito/Byte Buddy conhecidos. Logs `.local/b05-4a-*.log` ignorados. Dinheiro/domínio não são mocks; mocks ficam nas portas e Clock.

**Aceite pendente:** 13 cenários PostgreSQL foram escritos como caracterização vertical após o TDD unitário; não se alega red de integração independente. Criam transação pela aplicação real, aplicam/repetem resultado, releem consulta em nova transação e comparam histórico completo, dados monetários e contagens. Incluem instante textual equivalente com outro offset, nanos divergentes, causa desconhecida/de outra transação/tipo/versão incorretos e novo evento final. A primeira compilação encontrou erro da fixture que tratava BuscarTransacao como Optional; corrigida para seu contrato Resultado/exceção, sem alterar produção nem contar como red. Toda a suíte compila; execução real depende de CI porque Docker local segue indisponível. Não reler entidade JPA após UPDATE JDBC no mesmo contexto; retorno é a transição registrada. Concorrência fica em B05.4b, representação HTTP original em B05.4c.

**Aceite remoto:** [CI #121](https://github.com/Joaomagh/credpay/actions/runs/37149818123), SHA `43ec01b`, passou com 162 testes, zero falhas/erros/skips, em 1 min 5 s. Os 13 cenários PostgreSQL, 12 do caso de uso e oito da entrada passaram. Secret Scan #28 verde. Revisão assistida sem bloqueante; diff/UTF-8 conferidos antes da integração. Log do job sem warnings Hikari de conexão fechada e sem candidatos às formas limitadas de payload bruto pesquisadas; B08.1/B08.2 não estão globalmente resolvidos. [PR #97](https://github.com/Joaomagh/credpay/pull/97) exige checks do SHA final antes do merge. Próximo: serialização concorrente em B05.4b, mantendo a mesma aplicação e sem listener.

### 9.44 Serialização concorrente da aplicação do resultado — B05.4b

**Desenho em execução:** adquirir pg_advisory_xact_lock para eventId/transactionId antes de qualquer leitura, na transação READ_COMMITTED existente. Derivar hashtextextended(UUID como texto, 0) no banco, deduplicar/ordenar chaves Long efetivas antes da aquisição; reutilizar algoritmo do processador sem biblioteca compartilhada. Constraints seguem defesa final. Uma colisão de hash reduz paralelismo, não altera a decisão. Locks liberam no commit/rollback.

**Primeiro teste:** Clock controlado pausa a primeira aplicação antes do UPDATE; segunda chamada usa transação real e captura seu pg_backend_pid na mesma conexão. Exige advisory lock não concedido para esse PID antes de liberar primeira; depois compara resultados e histórico completo, contagem única, dinheiro preservado e uma leitura do Clock. Barreiras/executor têm finally e waits limitados. Código de serialização ainda ausente; red real aguardará CI, compilação e ausência de Docker não o substituem. Após green, incluir conflitos e liberação após rollback; falha controlada no Clock ocorrerá antes do SQL e não será apresentada como falha após escrita.

**Integração anterior:** PR #97 integrada em `acf6517` após CI #122/Secret Scan #29 verdes no último SHA `87926f9`. Sem listener ou nova dependência em B05.4b; revisão de desenho confirmou observação do PID específico e ordem das chaves efetivas.

**Red observado:** [CI #124](https://github.com/Joaomagh/credpay/actions/runs/37150409012), SHA `9fc5917`, executou 163 testes: 162 anteriores passaram; o novo falhou com segunda conexão não aguardou advisory lock. Banco/threads executaram o cenário real. Secret Scan #31 verde. Regressão local da ordem falhou na compilação somente porque bloquearIdentidades ainda não existia. Então a porta/adapter adquiriram hashes efetivos deduplicados e ordenados; a aplicação chama antes de ler histórico/transação. Dois cenários unitários verificam inversão das identidades e colisão dos hashes; próxima prova é green PostgreSQL, antes de acrescentar os demais conflitos/rollback.

**Primeiro green:** [CI #125](https://github.com/Joaomagh/credpay/actions/runs/37150664326), SHA `2d9f05d`, passou com 165 testes, zero falhas/erros/skips, incluindo disputa equivalente e regressões de ordem/deduplicação. Secret Scan #32 verde. Revisão encontrou risco P2 de orçamento da fixture: Clock aguardava 10 s, menor que início da segunda chamada (até 10 s) mais observação de lock (até 5 s). Foi ampliado para 30 s, sem reduzir/assertions/timeouts do coordenador e mantendo finally. Quatro divergências concorrentes e recuperação após rollback foram acrescentadas como caracterização do desenho existente, sem red inventado. Falha no Clock é anterior ao SQL; objetivo é comprovar liberação do lock, não repetir prova de atomicidade da segunda escrita. Aceite dos seis cenários depende de novo CI completo.

**Aceite e refatoração:** [CI #126](https://github.com/Joaomagh/credpay/actions/runs/37150907135), SHA `3033ec4`, passou com 170 testes, zero falhas/erros/skips, em 54 s. Seis cenários concorrentes passaram em 3 s, incluindo PID bloqueado específico, convergência, quatro conflitos e rollback liberando locks. Histórico completo/dados/contagem da vencedora preservados; transação alternativa permanece PENDENTE sem histórico. Secret Scan #33 verde. Extraídos métodos privados chaveDeLock/bloquear para explicar algoritmo, sem mudar SQL/ordem; verify offline pós-refatoração passou novamente com 72 testes e JAR. Compilação de toda a suíte, diff/UTF-8 e revisão assistida sem bloqueante; Docker local indisponível. Logs `.local/b05-4b-*.log` ignorados, warnings Mockito/Byte Buddy conhecidos. Log #126 sem Hikari fechado/candidatos às formas limitadas de payload bruto; não resolve globalmente B08.1/B08.2. [PR #98](https://github.com/Joaomagh/credpay/pull/98) exige checks no último SHA após refatoração/documentação antes de integrar. Nenhuma migration/dependência/listener novo. Próximo: preservar resposta original PENDENTE do POST idempotente coexistindo com GET final em B05.4c.

### 9.45 Replay da resposta HTTP original após estado final — B05.4c

**Decisão:** a representação de criação sempre terá PENDENTE, mesmo no replay após aplicação final; GET continua refletindo banco. Alterado somente CriarTransacaoService.paraResultado, sem UPDATE, DTO/snapshot extra ou modificar dados/escala originais. Dois testes unitários novos exigem resposta PENDENTE e objeto final intacto; red local executou oito testes com duas falhas esperadas (retornava APROVADA/REJEITADA).

**Teste vertical:** nova fixture HTTP/PostgreSQL para os dois estados cria pelo POST, aplica pelo caso de uso real e repete POST com escala equivalente diferente. Exige resposta/cabeçalho originais, GET final, escala original, um histórico completo e outbox de criação inteira intacta. MockMvcPrint.NONE somente nesta fixture e mensagens fixas para comparação de body/outbox evitam dump de payload financeiro em falha; diagnóstico de status permanece. Toda a suíte compila; Docker local indisponível exige CI para red/green real. O verify local sem infraestrutura passará a excluir **/*HttpTest (as duas fixtures HTTP precisam PostgreSQL), além de IntegrationTest/PostgresRuntimeTest; CI continua executando todos sem skips.

**Próximo refinado pelo P.O.:** B05.5a parser isolado, B05.5b topologia/políticas próprias opt-in, B05.5c listener com ack após commit e classificação/retry limitados, B05.5d fluxo dos dois aplicativos reais com bancos próprios. Saída construída pela fixture não contará como fluxo completo. Sem HA, tuning, painel ou replay operacional da DLQ em B05.

**Red e green local:** [CI #129](https://github.com/Joaomagh/credpay/actions/runs/37151788466), SHA fe2442b, executou 174 testes com quatro falhas esperadas, zero erros/skips: dois unitários e os dois HTTP esperavam PENDENTE, receberam APROVADA/REJEITADA. PostgreSQL real disponível; Secret Scan #36 verde. Depois da mudança mínima, oito focados e verify offline com 74 testes/JAR passaram; warnings Mockito/Byte Buddy conhecidos permanecem. Logs locais ignorados em .local/b05-4c-*.log. Green HTTP e revisão final pendentes antes de aceitar/integrar a PR #99.

**Aceite:** [CI #130](https://github.com/Joaomagh/credpay/actions/runs/37151996635), SHA d42e6cf, passou 174 testes sem falhas/erros/skips em 52 s, incluindo os dois HTTP com PostgreSQL real. Secret Scan #37 verde. Revisão assistida sem bloqueante; resposta de criação reconstrói o contrato original a partir dos dados imutáveis, sem persistir outro snapshot ou reiniciar o estado. Diff/UTF-8 serão conferidos e CI/scanner exigidos no último SHA documental antes de integrar a PR #99. B05.5a é o próximo resultado pronto; demais dependem dele.

### 9.46 Parser isolado do resultado — B05.5a

**Desenho:** TransacaoProcessadaMessageParser converte envelope JSON e propriedades AMQP em TransacaoProcessadaRecebida, sem bean/listener/banco. Valida objetos, campos textuais, versão inteira 1, UUIDs completos (hexadecimal aceita caixa como no parser de criação), correlação, dois status finais e propriedades iguais à identidade canônica. Instant conserva nanos e aceita representação com offset equivalente; campos extras são ignorados. Rejeições IllegalArgumentException têm mensagens fixas por campo e nenhuma causa com conteúdo externo; não adicionar amount/currency ao resultado nem abstração compartilhada entre serviços.

**TDD local:** API ausente deu red deliberado; primeira implementação mínima passou dois cenários finais com record completo/nanos. Matriz de validação mostrou 73 falhas entre 77 testes, incluindo identidade/propriedades aceitas indevidamente e erros de biblioteca que ecoavam conteúdo. Antes do green, removida expectativa incorreta de data={} produzir erro data: objeto vazio é estruturalmente objeto e falha pelo campo transactionId ausente, já coberto. Não contar essa expectativa como red de negócio. Depois das guardas, 77 focados passaram. Extras/offset e alguns formatos já rejeitados pela implementação mínima nasceram verdes, são caracterizações. Verify offline com 151 testes/JAR passou; warnings Mockito/Byte Buddy conhecidos permanecem. Logs ignorados .local/b05-5a-*.log; revisão e CI completos pendentes antes do aceite.

**Aprendizado:** parser protege a fronteira de transporte; causalidade e idempotência continuam no caso de uso transacional. Validar propriedade AMQP não confirma entrega ou commit. B05.5b prepara topologia/políticas antes de criar listener.

**Achado tratado na revisão:** leitura padrão aceitava objeto seguido de outro objeto ou lixo textual. Dois testes novos deram red: 79 executados, duas falhas por nenhuma exceção. ObjectReader local com FAIL_ON_TRAILING_TOKENS corrigiu o corpo inteiro sem mutar ObjectMapper compartilhado; 79 focados e verify offline com 153 testes/JAR verdes. A rejeição mantém JSON inválido sem causa. Sem ampliar para limites de payload ou política de campos duplicados; não há contrato específico desses itens neste slice.

**Aceite:** [CI #133](https://github.com/Joaomagh/credpay/actions/runs/37152699748), SHA a74ee1d, e [Secret Scan #40](https://github.com/Joaomagh/credpay/actions/runs/37152699539) verdes. Suíte completa inclui todos os 79 testes do parser e fixtures reais anteriores; reinspeção assistida sem bloqueante. Nenhum consumo ativado ou dependência adicionada. PR #100 exige checks do SHA documental final antes do merge. Próximo B05.5b usa políticas próprias e referência à exchange do produtor, sem declará-la na aplicação consumidora.

### 9.47 Topologia e políticas próprias do retorno — B05.5b

**Desenho em validação:** RabbitMqResultadoConfiguration opt-in por credpay.transacoes.consumer.topology.enabled (CREDPAY_TRANSACOES_CONSUMER_TOPOLOGY_ENABLED, padrão false). Duas quorum duráveis, DLX direct própria e bindings exatos; exchange credpay.processamento.v1 apenas referenciada, propriedade do processador. Nenhum listener/bean do parser novo; sem dependência. Políticas próprias em infra/rabbitmq/transacoes-policies.json, vhost /, prioridade 10, regex ancoradas, capacidades didáticas 10000/1000, reject-publish e dead-lettering at-least-once. Runbook TRANSACOES.md prepara/conferirá antes do futuro consumo; sem ativação fictícia ou implantação externa.

**Verificação local:** teste da API ausente deu red deliberado; configuração mínima passou dois cenários de padrão/false e true, incluindo ausência de declaração da exchange do produtor, filas/argumentos, exchanges e bindings. test-compile e verify offline com 155 testes/JAR passaram. Fixture de broker isolada de PostgreSQL/schedulers importa/reimporta o arquivo real, exige sentinelas residentes antes da segunda importação, compara políticas efetivas/argumentos/operator policy/flag por fila e escopo fora dos padrões. Outro cenário prova roteamento de sentinelas pelos dois bindings; isso não é consumo de evento de negócio ou dead-lettering por listener. Configuração operacional/caracterização não exige red de broker prévio; CI real ainda pendente. Filtros do workflow incluem artefato e runbook próprios.

**Limites:** conferência manual não bloqueia startup nem detecta alteração posterior. Nó único descartável não prova HA, tuning, teto estrito de capacidade ou recusa da DLQ de retorno. Artefato separado mantém políticas do processador intactas; não eliminar recursos para ajustar divergência. Logs locais ignorados .local/b05-5b-*.log; warnings Mockito conhecidos permanecem. Próximo listener depende deste aceite.

**Evidência real:** [CI transações #136](https://github.com/Joaomagh/credpay/actions/runs/37153259722), SHA 7538500, passou 257 testes sem falhas/erros/skips em 80 s; os dois novos de políticas/roteamento passaram em RabbitMQ real. Escopo protege inclusive fila de nome próximo v2. Secret Scan #43 verde; revisão assistida sem bloqueante. CI processador #149 também foi disparado pelo filtro compartilhado infra/rabbitmq/** e precisa terminar verde, além dos checks no último SHA documental antes de integrar a PR #101. Nenhuma prova de listener/ack/dead-lettering por rejeição foi inferida destas sentinelas.

### 9.48 Commit antes de ack no retorno experimental — B05.5c1

**Recorte em execução:** c1 comprovará caminho válido/replay; c2 classifica rejeições permanentes; c3 limita retry operacional. Só após c2/c3 será permitido anunciar consumidor operacional seguro e ativação manual. Flags de topologia e listener são ambas necessárias, listener padrão false; ativação prematura pode produzir reentrega ilimitada em falhas, portanto é proibida no runbook até os aceites. Sem dependência nova.

**TDD inicial:** teste exigiu classes ausentes, red deliberado; scaffolding opt-in/factory passou cinco casos de flags ausentes/false/combinações. Listener ainda é componente vazio, sem endpoint/comportamento, aguardando red remoto da fixture. Factory fixa prefetch 1; futuro endpoint usará AUTO e concorrência 1. Teste real compilado para dois estados finais: importar políticas antes de iniciar manualmente, pausar depois das escritas JDBC ainda na transação externa, outra conexão enxergar PENDENTE/sem histórico e broker mostrar entrega sem ack, depois exigir commit/histórico completo/dados/escala/outbox/Clock/ack. Pausa 45 s excede await inicial 10 s e observação do broker 15 s (CLI com timeout 5 s); liberação em finally e shutdown após cada caso. Docker local indisponível não é red.

**Integração anterior:** PR #101 integrada em 9a4d62d após CI transações #137 (257 testes), processador #150 (114 testes, 8m34s) e Secret Scan #44 verdes no último SHA d0506b4. Fixtures e políticas próprias comprovadas; não prova listener.

**Falha de isolamento descoberta:** [CI #139](https://github.com/Joaomagh/credpay/actions/runs/37154438604), SHA f99c29e, teve 263 testes e quatro erros, nenhuma falha de assertion. O novo teste no pacote messaging encontrou duas SpringBootConfiguration vizinhas, antes da preparação; a fixture antiga RabbitMqTopologyIntegrationTest usava ComponentScan amplo e importou beans das novas fixtures, causando conflito producerExchangeFixture. Não é o red esperado de negócio. Experimento mínimo: explicitar TransacoesServiceApplication no novo SpringBootTest e trocar scan da fixture antiga por imports somente de RabbitMqConfiguration/RabbitMqPublicadorEvento. Não habilitar override de bean nem reduzir testes; repetir CI antes de implementar endpoint.

**Red remoto correto:** [CI #140](https://github.com/Joaomagh/credpay/actions/runs/37154677037), SHA 8c28ff7, executou 264 testes, duas falhas esperadas, zero erros/skips: registry exigia um endpoint e tinha zero. Isolamento anterior corrigido; isto prova ausência do endpoint, ainda não a janela de commit. Depois deste red foi registrada a rota do listener, parser e chamada ao caso de uso transacional. AUTO/concorrência 1/prefetch 1 definidos explicitamente; fixture acrescenta configuração global conflitante, inspeção do container/consumidor exatos e DLQ vazia após conclusão. Green real ainda pendente.

**Primeiro green real:** [CI #141](https://github.com/Joaomagh/credpay/actions/runs/37154898821), SHA 8a11f6b, passou 264 testes sem falhas/erros/skips em 1m46s, inclusive duas janelas reais de commit/ack e configuração efetiva independente dos valores globais conflitantes. Verify offline passou 160/JAR. Replay equivalente foi então acrescentado como caracterização: observar segunda busca alvo ainda sem ack, conservar histórico inteiro/estado/outbox, uma escrita e uma consulta ao Clock; liberar e exigir origem/DLQ vazias. Preparação agora confere ambas políticas e flag no mesmo broker antes do start. Compilação e novo CI/revisão ainda pendentes.

**Aceite de c1:** [CI #142](https://github.com/Joaomagh/credpay/actions/runs/37155351518), SHA 8edd764, passou 265 testes sem falhas/erros/skips em 1m54s, incluindo três reais do listener (35,74 s). Replay nasceu verde como caracterização, não red artificial; preserva record completo, outbox original, Clock/escrita únicos e entrega observada antes do ack final. Secret Scan #49 verde. Compile/verify offline 160/JAR, diff/UTF-8 e revisão assistida sem bloqueante. README atualizado pelo marco público experimental; runbook exige listener false e proíbe ativação até c2/c3. CI processador também aplicável por mudança no runbook, além dos checks no último SHA documental, antes de integrar PR #102. c1 não encerra B05.5c.

### 9.49 Rejeições permanentes no retorno — B05.5c2

**Objetivo e decisão:** contrato inválido, conflito de identidade e recusa por transação/causa inválida rejeitam sem requeue; exceção AMQP traz diagnóstico fixo sem causa interna. Captura de IllegalArgumentException fica somente no parser, cujos erros são seguros; aplicação captura somente os dois tipos permanentes. Falhas de infraestrutura continuam propagadas, aguardando retry limitado em c3. Sem dependência, migration ou mudança de política. Listener permanece experimental e ativação operacional proibida.

**TDD local:** `mvnw.cmd --batch-mode --no-transfer-progress -o -Dtest=TransacaoProcessadaListenerTest test` executou quatro testes: três falhas esperadas pelo tipo de exceção original (JSON inválido/conflito/recusa), zero erros/skips. Após classificação mínima, quatro verdes. Falha operacional preserva a mesma exceção como caracterização já verde. Fixture real acrescenta JSON inválido, conflito depois de commit e recusa por causa desconhecida/transação desconhecida; exige corpo/identidades na DLQ, razão rejected, origem drenada e snapshot inteiro das três tabelas intacto. Verificação remota e revisão ainda pendentes; Docker local indisponível não é evidência de comportamento.

**Integração anterior:** PR #102 integrada em 99588cb após CI transações #143/processador #153 e Secret Scan #50 verdes no último SHA 0ebb6e8. c1 isoladamente não encerra B05.5c.

**Aceite de c2:** [CI #145](https://github.com/Joaomagh/credpay/actions/runs/37156850300), SHA 2c5ca82, passou 273 testes, zero falhas/erros/skips, inclusive sete reais do listener (57,29 s). Quatro casos novos provam DLQ com razão rejected/fila de origem exata, corpo/identidades preservados e snapshot completo intacto; três anteriores protegem commit/ack e replay. Secret Scan #52 verde; revisão assistida sem bloqueante recomendou conferir origem da rejeição, aplicado. Verify offline corrigido passou 164/JAR: primeira seleção incluiu PostgresRuntimeTest e falhou somente por Docker indisponível (165 testes, um erro), não red de negócio nem redução do CI. Comando local corrigido: `mvnw.cmd --batch-mode --no-transfer-progress -o '-Dtest=!**/*IntegrationTest,!**/*ApplicationTest,!**/*HttpTest,!PostgresRuntimeTest' verify`. Diff/UTF-8/scanner local aprovados. PR #103 depende de checks do último SHA documental antes de integrar. Mockito/Byte Buddy continuam com warnings conhecidos; ativação proibida até c3.

### 9.50 Retry operacional limitado no retorno — B05.5c3

**Objetivo e decisão:** usar Spring Retry já transitivo na baseline AMQP, sem dependência nova. Interceptor stateless na factory própria: três tentativas totais, backoff 1/2 s, exceção permanente AMQP em qualquer causa não sofre retry. Após esgotamento, rejeição fixa sem causa interna para DLQ. Outras exceções são tentadas limitadamente, sem alegar reconhecimento perfeito de transiência. Interceptor envolve listener; cada chamada ao caso de uso transacional abre sua transação, rollback precisa ser comprovado na fronteira real. AUTO/concorrência/prefetch 1 e flags padrão false preservados; ativação segue proibida até aceite e runbook atualizado.

**TDD local:** `mvnw.cmd --batch-mode --no-transfer-progress -o -Dtest=TransacaoProcessadaRetryConfigurationTest test`: quatro testes, três falhas esperadas (primeira falha operacional propagada sem recuperar, esgotamento sem rejeição segura, permanente envelopada sem recoverer), zero erros/skips. Configuração desligada já verde como caracterização. Após implementação mínima, retry/listener/configuração passaram 13 focados, incluindo três tentativas e pelo menos três segundos de espera total. Fixture real injeta falha depois de UPDATE/INSERT: duas falhas e sucesso no terceiro commit, ou três rollbacks e DLQ; snapshot inteiro durante pausa da segunda tentativa observa rollback anterior e invisibilidade das escritas atuais. Casos permanentes contam uma busca e nenhuma escrita. CI/revisão pendentes, sem red artificial de broker.

**Integração anterior:** PR #103 confirmada integrada em 34ba238 após CI #146/Secret Scan #53 verdes no SHA final 8bebdf0. Timeout na chamada de merge exigiu confirmação posterior antes de implementar c3; nenhum merge duplicado.

**Aceite de c3:** [CI #148](https://github.com/Joaomagh/credpay/actions/runs/37157646659), SHA 68e072c, passou 279 testes sem falhas/erros/skips em 2m49s; nove reais do listener passaram em 76,16 s. Segunda tentativa após SQL permitiu observar snapshot externo intacto/entrega sem ack, terceiro commit final único; esgotamento mostrou três escritas tentadas/Clock e rollback integral antes da DLQ. Permanentes conservaram uma busca/nenhuma escrita. Secret Scan #55, 168 offline/JAR e revisão assistida verdes. Espera unitária comprova mínimo total de três segundos, intervalos individuais são configuração inspecionada. Runbook agora permite ativação manual depois de conferir políticas/flag/bindings/consumidor exatos; README atualizado pelo marco. CI do processador também aplicável pela mudança do runbook, além de checks no SHA documental final antes de integrar PR #104. B05.5d permanece pendente; não alegar fluxo completo ou HA.

### 9.51 Primeiro fluxo vertical dos aplicativos reais — B05.5d1

**Objetivo e decisão:** caracterizar o comportamento já implementado com dois JARs reais do mesmo checkout em processos Java separados, dois PostgreSQL próprios e RabbitMQ descartável. JUnit/Testcontainers/HTTP Java/JDBC já existentes, sem dependência nova ou importação entre serviços. Workflow dedicado compila ambos e seleciona explicitamente FluxoCredPayE2E; nome fora do padrão Surefire separa essa prova das suítes completas que continuam obrigatórias. Não fabricar resultado por SQL ou evento de saída. Scaffolding/orquestração operacional e caracterização não ganham red artificial; qualquer correção de comportamento descoberta exigirá red legítimo antes de produção.

**Preparação:** três fases limitadas com cleanup entre processos: exchanges produtoras sem consumo/publicação/topologias; topologias e importação/conferência efetiva das políticas sem consumidores; só então ambas aplicações com consumo/publicação. Health Rabbit é explicitamente true na preparação, pois health geral com Rabbit desabilitado não é evidência de conexão/declaração. Broker confere recursos reais, flag, bindings e consumidores/ack/prefetch exatos. POST deve percorrer processador real e ambas outboxes antes do GET final; inspeção inclui causalidade/histórico único, instante do resultado, dados/escala e propriedade dos bancos. Dup/replay ficam em d2.

**Verificação inicial:** compilação local do teste passou sem Docker. Revisão assistida apontou cleanup incompleto sob interrupção/assertion e risco de corrida após health; ajustes preservam referências de sobreviventes, tratam todos os filhos e aguardam consumidores exatos com prazo. Recompilação e CI real ainda pendentes; logs dos processos ficam em .local/e2e ignorado, sem upload/dump integral. Docker local indisponível não comprova fluxo nem é red.

**Integração anterior:** PR #104 integrada em c0237e3 após CI transações #150/processador #156/Secret Scan #57 verdes no SHA final b95ca5b. Processador #156 passou 114 testes em 8m21s com 40 warnings Hikari conhecidos; suites separadas. B05.5c encerrado; B05 completo ainda não.

**Primeiro aceite vertical:** [Application Flow CI #1](https://github.com/Joaomagh/credpay/actions/runs/37159251469), SHA f5d5795, compilou ambos JARs e passou dois cenários reais sem falhas/erros/skips em 65,11 s (comando focado 1m08s). Três fases declararam/conferiram recursos pelos aplicativos reais, nenhum consumidor na preparação, dois consumidores exatos após ativação e bancos próprios sem tabela do outro agregado. POST original PENDENTE alcançou ambos finais por decisão real do processador; ambas outboxes publicadas, causa/correlação/instante/histórico único/dados/escala e quatro filas vazias comprovados. Caracterização nasceu verde, sem red artificial. Secret Scan #59 verde; suíte transações e checks finais ainda exigidos para integrar PR #105. README/badge/runbook acompanham o marco; d2 ainda necessário para B05. Busca limitada no log do job vertical não encontrou JSON financeiro bruto nos formatos pesquisados; não encerra B08.1.

**Regressão de d1:** [CI transações #152](https://github.com/Joaomagh/credpay/actions/runs/37159251349), mesmo SHA f5d5795, passou 279 testes sem falhas/erros/skips. Teste vertical é selecionado somente no workflow dedicado; não substitui nem altera a suíte do módulo. Reinspeção assistida confirmou tratamento dos dois achados, sem novo bloqueante. PR #105 requer repetição dos checks no SHA documental final antes do merge.

### 9.52 Duplicata e replay no fluxo real — B05.5d2

**Objetivo e decisão:** estender os mesmos cenários APP/REJ dos dois JARs reais. Republicar entrada e saída a partir das linhas reais das outboxes: mesmo payload, eventId, type/correlationId e propriedades JSON/UTF-8/persistência dos publicadores existentes. Harness usa confirms correlacionados/mandatory e rejeita returns; não fabrica nova decisão/resultado. Cinco tabelas dos dois bancos são comparadas inteiramente após duplicatas e replay HTTP, com mensagens de falha fixas sem dump financeiro. POST mesma chave com escala equivalente deve conservar corpo/Location original PENDENTE; GET conserva o resultado final.

**Observabilidade do teste:** API HTTP de [RabbitMQ 4.3](https://www.rabbitmq.com/docs/http-api-reference), no broker descartável com credenciais de teste, observa contagem ack por fila; primeiro aguardar originais mais duplicatas anteriores, depois exigir avanço após republicação confirmada. Prazos limitados e nenhuma resposta/credencial integral publicada. Filas vazias sozinhas não comprovam que a duplicata foi processada. Mesmo thread explicita ordem dos cenários/contadores; factory da republicação é fechada no cleanup. Campo ack na versão/digest fixados ainda exige prova real; sem alegar sucesso por compilação ou documentação.

**Estado:** compilação inicial passou, revisão e CI real pendentes. Caracterização de idempotência já implementada, sem mudança de produção/dependência ou red artificial. Falha de comportamento descoberta exigirá red legítimo antes de correção. D1 integrado na PR #105 em 1e5e3d3 após Application Flow #2/CI transações #153/Secret Scan #60 verdes no SHA final 1c6c94f.

**Aceite de d2:** [Application Flow CI #4](https://github.com/Joaomagh/credpay/actions/runs/37160110175), SHA b3fc12e, passou dois cenários reais em 90,64 s (comando 1m32s), zero falhas/erros/skips. Ambos estados finais percorreram os dois JARs; duplicatas de ambas outboxes tiveram confirms sem returns e avanço de ack real, cinco tabelas inteiras preservadas e POST com escala equivalente conservou corpo/Location PENDENTE/GET final. Campo ack confirmado na versão/digest fixados; contador agregado é suficiente neste broker isolado com produtores/eventos conhecidos, não identifica individualmente cada entrega ou ausência geral de duplicatas. [CI transações #155](https://github.com/Joaomagh/credpay/actions/runs/37160110167) passou 279 testes em 2m52s; Secret Scan #62 e compilação/revisão assistida sem bloqueante. Caracterização nasceu verde. README/runbook acompanham o marco; checks no SHA documental final exigidos antes de integrar PR #106 e encerrar B05.

**Revisão de produto próxima:** P.O./dev sênior refinaram B06.1 (processador parado/reinício, fila durável/PENDENTE e recuperação causal) e B06.2 (mensagem real na DLQ após moeda sem limite, corrigir configuração e republicar antes de ack da remoção). Sem implantação ou dependência nova. FALHOU segue sem gatilho definido: questão enviada ao Navigator, sem converter esgotamento/DLQ em resultado financeiro. B08.2 fica frente separada, com diagnóstico causal pendente.

### 9.53 Processador parado e recuperação real — B06.1

**Objetivo/decisão:** caracterizar a durabilidade existente com parada controlada somente do JAR do processador. Dois bancos/broker e aplicativo de transações permanecem ativos. Sem comportamento de produção, biblioteca ou dependência nova; não exige red artificial.

**Experimento:** exigir ausência do consumidor de entrada e presença do consumidor de resultado antes do POST; conferir PENDENTE, outbox original publicada, exatamente uma mensagem pronta/zero unacked e nenhuma decisão/saída/histórico. Reiniciar o processador no mesmo banco/broker; exigir GET APROVADA, cadeia de IDs original, cinco registros únicos, dados da transação preservados além do status, outbox original intacta e quatro filas vazias. Cleanup seleciona somente processos filhos alvo e mantém referências de sobreviventes. Replay precede reinício para conservar suas baselines AMQP; recuperação funciona isoladamente.

**Validação:** `.\mvnw.cmd --batch-mode --no-transfer-progress -o -DskipTests test-compile`, no módulo de transações, passou (31 fontes de teste, Java 21). [Flow CI #7](https://github.com/Joaomagh/credpay/actions/runs/37161171631) executou três cenários reais sem falhas/erros/skips, 109,2 s; [transações #158](https://github.com/Joaomagh/credpay/actions/runs/37161171462) passou 279 testes e gerou JAR, 2m57s; [Secret Scan #65](https://github.com/Joaomagh/credpay/actions/runs/37161171469) verde em `5f07590`. Revisão assistida sem bloqueante; mensagem de falha do snapshot HTTP foi fixada para não imprimir JSON. Busca limitada no log do Flow encontrou zero candidatos de JSON financeiro integral; não substitui B08.1. Transações registrou 29 warnings Hikari de conexão fechada, mantidos em B08.2 sem supressão. Docker Linux local segue indisponível. `git diff --check`, UTF-8 estrito e hook de segredos passaram. A integração exige repetir checks no SHA final.

**Limites/próximo:** encerramento controlado não comprova crash abrupto, HA ou queda de broker. B06.2 provará correção de configuração e replay de DLQ preservando mensagem até confirmação; definição de FALHOU continua aguardando direção de produto do Navigator.

### 9.54 Replay após correção de configuração — B06.2

**Objetivo/decisão:** caracterizar falha operacional por moeda válida sem limite (USD) e recuperação depois de reiniciar somente o processador com USD100, no mesmo banco/broker. Sem código de produção ou dependência nova; cliente Java RabbitMQ já transitivo. Valor acima do limite continua decisão REJEITADA normal, sem DLQ.

**Aceite:** POST/GET PENDENTE, outbox original publicada, mensagem isolada na DLQ com diagnóstico fixo de esgotamento e nenhuma decisão/saída/histórico. Replay mandatory sem rota precisa retornar e conservar mensagem/bancos. Após correção, mensagem real é lida por `basicGet(false)`, corpo/propriedades/headers/identidade conferidos, republicada e aguardada por `waitForConfirmsOrDie`; return impede ack. Só confirm sem return permite `basicAck` da DLQ. Recuperação exige APROVADA USD, limite100, causa original, cinco registros únicos, dados/outbox original intactos e filas vazias.

**Fundamento:** [guia do cliente Java](https://www.rabbitmq.com/client-libraries/java-api-guide) descreve leitura com ack manual; [confirms RabbitMQ](https://www.rabbitmq.com/docs/confirms) especifica return antes de ack para publicação mandatory sem rota. Conexão/canal exclusivos por tentativa, recuperação automática do cliente desligada e fechamento sem ack permitem reentrega em falha. Confirmação antes de ack evita retirar a única cópia antes de publicação, mas queda nessa janela pode duplicar; identidade/idempotência permanecem necessárias.

**Estado/evidência:** `.\mvnw.cmd --batch-mode --no-transfer-progress -o -DskipTests test-compile`, no módulo de transações, passou após asserções adicionais (31 fontes, 14,312 s). Revisão assistida sem bloqueante; [Flow CI #10](https://github.com/Joaomagh/credpay/actions/runs/37161905170) passou quatro cenários reais sem falhas/erros/skips, 122,1 s; [transações #161](https://github.com/Joaomagh/credpay/actions/runs/37161905148) passou 279 testes e JAR, 2m44s; [Scan #68](https://github.com/Joaomagh/credpay/actions/runs/37161905123) verde em `2fd5ac7`, PR #108. UTF-8/diff/hook passaram. Busca limitada no log do Flow encontrou zero candidatos de JSON financeiro integral, sem substituir B08.1. Transações registrou 30 warnings Hikari, não suprimidos. Integração aguarda checks do SHA final. Política de três tentativas já tem prova unitária/integrada do listener; este cenário observa esgotamento/diagnóstico e DLQ, sem instrumentar contagem de invocações dentro do JAR. Não é ferramenta operacional genérica nem autorização para replay externo. FALHOU continua aguardando regra de produto. Próximo após integração: B08.2a, comparação causal de lifecycle de fixture.

### 9.55 Ciclo de vida de uma fixture PostgreSQL — B08.2a

**Hipótese/experimento mínimo:** contexto Spring cacheado conserva Hikari depois que Testcontainers para PostgreSQL da classe. Na fixture CriarTransacaoConcorrenciaIntegrationTest, capturar o datasource original no início do único teste e exigir pool fechado/container ainda ativo imediatamente antes de `super.stop()`. Marcador imprime somente nome do pool/booleanos, sem URL/credencial; `finally` mantém encerramento do container mesmo no red. Ausência de referência/container parado não é green.

**Ordem/aceite:** durante o experimento, CI executou CriarTransacaoConcorrenciaIntegrationTest → TransacaoProcessadaListenerIntegrationTest com `-Dtest=... -Dsurefire.runOrder=alphabetical test`, depois suíte completa. Red precisava observar pool aberto antes do stop. Só depois corrigir a primeira fixture e repetir o mesmo par/ordem, conferindo fechamento antes do container e warnings atribuídos; outros pools não são presumidos corrigidos. Sem logger suprimido, dependência ou código de produção alterado. Docker local indisponível; evidência real obtida no CI abaixo.

**Preparação:** container anônimo com diamond inicialmente não compilou pelo SELF recursivo do Testcontainers; não foi red de lifecycle. Subclasse local com SELF explícito resolveu. `.\mvnw.cmd --batch-mode --no-transfer-progress -o -DskipTests test-compile` passou (31 fontes, 5,353 s). Nenhuma correção de fechamento aplicada antes da observação real.

**Red observado:** [CI transações #164](https://github.com/Joaomagh/credpay/actions/runs/37170132953), SHA `45e12d5`, PR #109: marcador `HikariPool-1 closed=false postgresRunning=true`; falha exclusiva `pool da fixture de concorrência deve fechar antes do PostgreSQL` no teardown. Concorrência passou; nove cenários do listener passaram (69,15 s). Surefire agregou 11 execuções com um erro de callback; suíte completa não iniciou pois o par falhou. Não houve warnings de conexão fechada nessa janela: não se conclui redução por sua ausência.

**Correção mínima:** `@DirtiesContext(methodMode = AFTER_METHOD)` no único método da primeira fixture fecha/retira contexto do cache após o teste, antes do encerramento do container estático ao terminar a classe JUnit. Par e suíte completa repetidos; B08.2a não declara outras fixtures corrigidas.

**Green observado:** [CI transações #165](https://github.com/Joaomagh/credpay/actions/runs/37170477511), SHA `5635517`: par alfabético passou dez testes sem falhas/erros/skips (1m36s); suíte completa passou 279 e JAR (2m15s). Nas duas execuções, `HikariPool-1 - Shutdown completed` precedeu `closed=true postgresRunning=true`, comprovando fechamento antes do stop. Pool da concorrência teve zero warnings; baseline completa #161 associava dez a essa classe/pool sem shutdown. Outros pools ainda emitiram cinco (Atomicidade) e sete (PublicarOutbox); diferença de contagem/duração não comprova correção deles. Par red e green não tinham warnings, logo redução não é inferida da janela curta. [Flow #14](https://github.com/Joaomagh/credpay/actions/runs/37170477520) e [Scan #72](https://github.com/Joaomagh/credpay/actions/runs/37170477530) verdes. Compilação green offline passou; revisão assistida sem bloqueante. Busca limitada do log encontrou zero candidatos de JSON financeiro integral, sem substituir B08.1. Job com par+suíte ficou abaixo de dez minutos. Integração depende dos checks do SHA final.

**Aprendizado/próximo:** cache Spring e container JUnit têm ciclos diferentes; encerrar o banco não encerra automaticamente o contexto/pool. A asserção direta evita depender do momento do housekeeping. Refinado B08.2b somente para a fixture Atomicidade, que tem um único teste: conservar rollback das três tabelas e usar o mesmo red/green de lifecycle. Não espalhar correção a fixtures não diagnosticadas.

**CI permanente:** após par red/green e suítes completas #165/#166 verdes, revisão retirou somente a etapa temporária que duplicava dez testes. A asserção de teardown permanece na fixture descoberta pelo `verify`; todos os 279 cenários e os checks Flow/Scan continuam exigidos no SHA final. Comando/evidência do par são preservados acima; nenhum cenário foi removido ou falha contornada.

**Integração:** [PR #109](https://github.com/Joaomagh/credpay/pull/109), merge `caf4174`, após transações #168/Flow #17/Scan #75 verdes no SHA final `e6447ff`. Suíte final: 279 testes sem falhas/erros/skips, 2m55s, mesmo shutdown/closed=true antes do stop e zero warnings próprios; Atomicidade/PublicarOutbox ainda dez cada. Revisão final tratou redação obsoleta de preparação; nenhum review remoto pendente.

### 9.56 Ciclo de vida da fixture Atomicidade — B08.2b

**Objetivo/aceite:** repetir diagnóstico somente em CriarTransacaoAtomicidadeIntegrationTest, preservando único cenário de falha simulada na fronteira outbox, exceção esperada e transação/idempotência/outbox vazias após rollback. Datasource original capturado no início; teardown exige pool fechado com PostgreSQL ativo, `finally` garante stop. Sem correção antes de red observado.

**Experimento:** par Atomicidade → TransacaoProcessadaListenerIntegrationTest em ordem alfabética, depois suíte completa. Red precisa ser exclusivo de lifecycle com rollback e listener verdes; startup/compilação não contam. Green usa fechamento após único método. Baseline #168 associa dez warnings à Atomicidade (pool2); número no par difere, conferir classe/contexto. Após prova, retirar etapa temporária do par e manter asserção permanente na suíte. Sem produção/dependência/logger alterado; não amplia cobertura do mock para falhas reais de outbox. Docker local indisponível; resultados reais abaixo.

**Red observado:** [CI transações #170](https://github.com/Joaomagh/credpay/actions/runs/37171581841), `889ddc8`, PR #110: marcador Atomicidade `HikariPool-1 closed=false postgresRunning=true`; falha exclusiva `pool da fixture Atomicidade deve fechar antes do PostgreSQL` no teardown. Cenário de rollback passou; nove casos do listener passaram (78,75 s). Agregação de 11 execuções com um erro de callback, sem iniciar suíte completa. Janela não emitiu warnings, sem conclusão de redução por isso. Compilação red offline passou (31 fontes, 27,806 s), revisão sem bloqueante, diff/UTF-8/hook/Scan #77 verdes.

**Correção mínima:** DirtiesContext AFTER_METHOD no único teste fecha contexto/pool antes do stop da classe; todos os cenários/assertions de rollback mantidos. Compilação green offline passou (31 fontes, 18,401 s); revisão sem bloqueante.

**Green observado:** [CI transações #171](https://github.com/Joaomagh/credpay/actions/runs/37171812129), `a5e0510`: par passou dez testes sem falhas/erros/skips (1m25s), suíte completa passou 279/JAR (1m51s). Shutdown completed precedeu Atomicidade closed=true/PostgreSQL ativo nas duas execuções (pool1 no par, pool2 na suíte). Nenhum warning de conexão fechada nessa janela; fechamento direto comprova esta fixture, mas duração menor não prova correção de PublicarOutbox. [Flow #20](https://github.com/Joaomagh/credpay/actions/runs/37171812159)/[Scan #78](https://github.com/Joaomagh/credpay/actions/runs/37171812143) verdes. UTF-8/diff/hook/revisão passaram. Após prova, etapa temporária do par retirada; asserção e todos os cenários permanecem no verify obrigatório. Integração exige checks do SHA final.

**Integração:** [PR #110](https://github.com/Joaomagh/credpay/pull/110), merge `f6e0dcf`, após transações #172/Flow #21/Scan #79 verdes no SHA final `0a0725b`. Suíte final passou 279 (2m47s); Concorrência/Atomicidade fecharam antes do stop, zero warnings próprios, PublicarOutbox ainda dez. Revisão sem pendência remota.

### 9.57 Ciclo de vida da fixture PublicarOutbox — B08.2c

**Objetivo/aceite:** conservar os dois cenários reais de publicação/recuperação após corrigir rota, corpo/identidade/propriedades, limpeza SQL e filas descartáveis. Capturar os pools originais antes do BeforeEach SQL; execução SAME_THREAD, lista de referências preservada. Subclasse local PostgreSQL com SELF explícito observa todos os pools fechados e banco ativo imediatamente antes de super.stop(); finally garante limpeza mesmo no red. Marcador só imprime nome/booleanos, sem URL/credencial.

**Decisão revisada:** observar em AfterAll do usuário exigiria fechamento antes do limite necessário: callback Spring AFTER_CLASS ainda pode fechar o contexto depois. No [Testcontainers 1.21.4](https://github.com/testcontainers/testcontainers-java/blob/1.21.4/modules/junit-jupiter/src/main/java/org/testcontainers/junit/jupiter/TestcontainersExtension.java), o StoreAdapter fecha o container no encerramento do store JUnit. Observar o stop real permite testar AFTER_CLASS e conservar um contexto para os dois cenários, em vez de impor recriação por método. Nenhum cache é fechado manualmente; sem dependência/produção/logger alterados.

**Experimento:** par PublicarOutboxIntegrationTest → TransacaoProcessadaListenerIntegrationTest com `-Dtest=PublicarOutboxIntegrationTest,TransacaoProcessadaListenerIntegrationTest -Dsurefire.runOrder=alphabetical test`, depois verify completo. Red precisa observar pool aberto com PG ativo e cenários de negócio verdes; startup/compilação não contam. Baseline #172 atribui dez warnings a PublicarOutbox; ausência na janela curta não prova correção. Par temporário sai após prova, asserção permanece na suíte. Compilação offline `-DskipTests test-compile` passou: 31 fontes, 23,217s. Docker local indisponível; evidência real pelo CI. FALHOU aguarda decisão de produto.

**Red observado:** [CI transações #174](https://github.com/Joaomagh/credpay/actions/runs/37172851730), `8314dcb`, PR #111: duas capturas do mesmo HikariPool-1 `closed=false postgresRunning=true`; única falha `todos os pools da fixture PublicarOutbox devem fechar antes do PostgreSQL`. Os dois cenários da outbox passaram, listener passou nove (76,30s); agregação 12 execuções com um erro de callback, total 1m45s. Verify completo não iniciou pois o par falhou. Sem warnings nessa janela, sem inferência de redução. Diff/UTF-8/revisão/hook verdes.

**Correção mínima:** AFTER_CLASS fecha contexto/pool depois dos dois cenários e antes do stop real. Lista preserva ambas as referências do contexto compartilhado; nenhum cenário, reset SQL ou fila alterado. Compilação green offline passou: 31 fontes, 32,862s; revisão sem bloqueante.

**Green observado:** [CI transações #175](https://github.com/Joaomagh/credpay/actions/runs/37173081208), `cb413ee`: par11 verde (1m48s), suíte279/JAR verdes (2m26s), sem falhas/erros/skips. Shutdown completed precedeu as duas capturas `closed=true postgresRunning=true` do mesmo pool: pool1 no par, pool3 na suíte. Nenhum warning de conexão fechada registrado; baseline #172 tinha dez próprios. Prova de lifecycle é a observação direta, não o contador de warnings; demais fixtures não diagnosticadas não são presumidas corrigidas. [Flow #24](https://github.com/Joaomagh/credpay/actions/runs/37173081177)/[Scan #82](https://github.com/Joaomagh/credpay/actions/runs/37173081331) verdes. Busca limitada do log encontrou zero candidatos às formas pesquisadas de JSON financeiro, sem concluir B08.1. Hook/diff/UTF-8/revisão passaram.

**Entrega/limites:** retirado somente o par temporário após prova; asserção permanece no verify com todos os 279 cenários. Checks do SHA final exigidos antes do merge. Próximo B08.2d: concorrência do processador, cinco execuções/contexto compartilhado e red/green diretamente na suíte padrão114, sem duplicação em job de dez minutos. CI #156 levou 8m21s e registrou dez warnings próprios; timeout/falha de startup não serão red de lifecycle. Sem mudanças nos locks/contadores/cenários nem dependência da definição de FALHOU.

**Integração B08.2c:** [PR #111](https://github.com/Joaomagh/credpay/pull/111), merge `b6bf7ae`, depois de transações #176/Flow #25/Scan #83 verdes em `b258b26`. Suíte final279/2m50s, sem falhas/erros/skips; shutdown antes dos guards Concorrência/Atomicidade e duas referências PublicarOutbox, zero warnings de conexão fechada nessa execução. Revisão final sem bloqueante ou review remoto pendente.

### 9.58 Ciclo de vida da concorrência do processador — B08.2d

**Objetivo/aceite:** diagnosticar somente RegistrarProcessamentoConcorrenciaIntegrationTest. Cinco execuções (equivalentes, três divergências, rollback/recuperação) permanecem com UUIDs distintos, gates/locks reais e contadores originais. Capturar todos os pools no BeforeEach antes do reset; SAME_THREAD protege a lista sem alterar a disputa executada pelos dois workers. Guard antes de super.stop() exige lista presente, PG ativo e todos os pools fechados; finally preserva limpeza, marcador só nome/booleanos.

**Baseline/decisão:** [CI processador #156](https://github.com/Joaomagh/credpay/actions/runs/37158090999), `b95ca5b`, passou114/8m21s; concorrência passou5/14,53s. HikariPool-1 pertence a essa fixture, dez warnings sem shutdown registrado; outros pools2/3/5 dez cada. Red/green usam o mesmo `./mvnw --batch-mode --no-transfer-progress verify`, ordem padrão/workflow/timeout de dez minutos intactos. Sem par extra que duplicaria janela longa. A observação direta no stop prova lifecycle sem depender de warnings ou classe subsequente.

**Protocolo:** primeiro obter red exclusivo de pool aberto/PG ativo com os cinco cenários e demais testes verdes; timeout/startup/compilação não contam. Só então AFTER_CLASS para conservar contexto compartilhado, cinco capturas fechadas antes do stop e suíte114 verde. Sem produção/dependência/logger alterados nem correção automática de outras fixtures. Docker local indisponível; teste real pelo CI. Compilação red offline `-DskipTests test-compile` passou (23 fontes, 33,869s); red real registrado abaixo.

**Red observado B08.2d:** [CI processador #158](https://github.com/Joaomagh/credpay/actions/runs/37173766401), `8f6dbcc`, PR #112: cinco capturas do mesmo pool1 `closed=false postgresRunning=true`; única falha `todos os pools da concorrência do processador devem fechar antes do PostgreSQL` no teardown. Cinco cenários e todos os demais testes passaram: agregação115 com um erro de callback, zero skips, total8m12s. Dez warnings próprios, outros pools2/3/5 tiveram10/6/10; diferença de contagem não prova correção. [Flow #27](https://github.com/Joaomagh/credpay/actions/runs/37173766412)/[Scan #85](https://github.com/Joaomagh/credpay/actions/runs/37173766363) verdes. Revisão/diff/UTF-8/hook passaram.

**Correção mínima:** AFTER_CLASS fecha contexto compartilhado após as cinco execuções, antes do stop real; nenhuma mudança nos gates/locks/contadores. Compilação green offline passou (23 fontes, 9,274s), revisão sem bloqueante após corrigir frase obsoleta de red pendente.

**Green observado:** [CI processador #159](https://github.com/Joaomagh/credpay/actions/runs/37174286470), `be099cf`, passou114/8m16s, zero falhas/erros/skips. Cinco cenários passaram (19,83s); Shutdown completed precedeu as cinco capturas do mesmo pool1 `closed=true postgresRunning=true`. Zero warnings próprios versus dez no red #158/baseline #156. Outros pools2/3/5 ainda10/8/10; contagem menor no pool3 não prova correção. [Flow #28](https://github.com/Joaomagh/credpay/actions/runs/37174286461)/[Scan #86](https://github.com/Joaomagh/credpay/actions/runs/37174286463) verdes. Busca limitada do log encontrou zero candidatos às formas pesquisadas de JSON financeiro, sem concluir B08.1. Workflow/suíte/timeout intactos; sem teste extra ou logger suprimido. Hook/diff/UTF-8/revisão passaram; checks do SHA final exigidos antes do merge.

**Próximo refinado:** B08.3, instrumentação Mockito explícita apenas no fork Surefire. Dependency:tree offline nos dois módulos confirmou Mockito5.17.0/ByteBuddyAgent1.17.8/plugin dependency3.8.1. Controle antes com `-DargLine=-XX:-EnableDynamicAgentLoading` e testes existentes falhou exclusivamente ao inicializar inline mock maker: TransacaoCriadaListenerTest2 erros, PublicarOutboxServiceTest5 erros; logs ignorados em .local/b083-*-before.log. Não é red de negócio e nenhum POM foi alterado neste incremento. Depois, B07.1 refina imagens/startup na v1; o diagnóstico adicional B08.2e fica no backlog.

**Integração B08.2d:** [PR #112](https://github.com/Joaomagh/credpay/pull/112), merge `38cadc2`, após processador #160/Flow #29/Scan #87 verdes em `28333c1`. Final114/8m20s, sem falhas/erros/skips; shutdown antes das cinco capturas fechadas, zero warnings próprios. Outros pools2/3/5 ainda dez cada. Revisão final sem bloqueante/review remoto pendente. Topologia RabbitMQ levou cerca de370s em baseline/red/green; esses tempos são de teste, sem inferência de latência do produto.

### 9.59 Instrumentação Mockito explícita no fork de testes — B08.3

**Problema/baseline:** CI #156 registra self-attach/dynamic-agent warnings; Mockito5.17.0/ByteBuddyAgent1.17.8 já são transitivos de starter-test3.5.16. Dependency:tree offline confirmou ambos módulos e plugin dependency3.8.1; Surefire3.5.6 observado no CI #160. Nenhuma versão ou dependência muda. Configuração operacional não exige red artificial de negócio.

**Decisão/aceite:** [Mockito da versão](https://javadoc.io/static/org.mockito/mockito-core/5.17.0/org.mockito/org/mockito/Mockito.html#0.3) orienta agente no startup da JVM de teste. [dependency:properties3.8.1](https://maven.apache.org/plugins-archives/maven-dependency-plugin-3.8.1/properties-mojo.html) resolve caminho do JAR transitivo na fase initialize. POM de cada serviço usa argLine padrão vazio, substituição tardia `@{argLine}` e caminho do agente entre aspas, preservando argumentos adicionais. CI de cada módulo passa `-DargLine=-XX:-EnableDynamicAgentLoading`: mocks devem funcionar com anexação dinâmica impedida. Sem mudança de cenário/descoberta/timeout; suítes114/279 e Flow exigidos. Agente restrito ao fork Surefire; não configurar JAVA_TOOL_OPTIONS, manifest ou comando dos JARs reais.

**Controle antes/depois:** comandos offline `-Dtest=TransacaoCriadaListenerTest` e `-Dtest=PublicarOutboxServiceTest` com `-DargLine=-XX:-EnableDynamicAgentLoading` falharam antes exclusivamente na inicialização inline/self-attach (2/5 erros, sem skips). Após POM, mesmos comandos passaram2/5 (24,915s/17,476s), sem self-attach/dynamic-agent warnings. Logs .local/b083-*-before/after.log ignorados. São controles de configuração; não reds de negócio, sem novos testes redundantes.

**Verificação local:** verify offline passou75/JAR no processador (24,855s) e168/JAR em transações (22,584s), anexação dinâmica impedida. Zero self-attach/dynamic warnings; aviso CDS permanece (compartilhamento limitado após append no bootstrap classpath), sem supressão. Inspeção dos dois JARs encontrou zero entradas Mockito/byte-buddy-agent. XML/UTF-8/diff passaram; revisão de POMs/workflows sem bloqueante. CI real detalhado abaixo.

**CI do código em `75a052a`:** [processador #162](https://github.com/Joaomagh/credpay/actions/runs/37175878486) passou114/8m30s e [transações #178](https://github.com/Joaomagh/credpay/actions/runs/37175878493) passou279/3m00s, ambos com anexação dinâmica desativada, zero self-attach/dynamic-agent warnings e um aviso CDS cada. Processador: pool1 zero warnings, pools2/3/5 dez cada; transações zero warnings de conexão fechada nessa execução. [Flow #31](https://github.com/Joaomagh/credpay/actions/runs/37175878520) passou os quatro cenários reais (101,3s, Maven1m43s), sem esses warnings; [Scan #89](https://github.com/Joaomagh/credpay/actions/runs/37175878479) verde. O agente não entrou nos processos dos JARs. Revisão final sem bloqueantes; gates finais em df41c0c: processador #163/run37176507766 passou114/8m11s, transações #179/run37176507758 passou279/2m43s, Flow #32/run37176507692 passou4/2m02s e Scan #90/run37176507712 verde. Self-attach/dynamic0, CDS1 em cada módulo; pool1processador0 warnings e pools2/3/5 dez cada, transações0. PR #113 integrada em74ba2b2 após gates/revisão; sem reviews/threads pendentes.

**Falha entendida do seletor local:** primeira seleção de transações sem exclusão E2E executou168 verdes e incluiu FluxoCredPayE2E, cujo startup falhou exclusivamente por Docker indisponível (agregação169/1 erro, 28,326s). Seletor local corrigido exclui explicitamente FluxoCredPayE2E; nenhuma alteração de teste/CI para obter green. Verify padrão continua279 e workflow vertical continua quatro cenários reais. Comando corrigido em11; relatórios antigos em target não contam como falhas dessa execução.

**Limites:** não usar flag que habilite anexação dinâmica ou CDS-off para esconder warnings. IDE/execução Maven sem fork exigem configuração própria, fora deste incremento. Ausência de warning não substitui mocks funcionando e suítes reais; Docker local indisponível, integração pelo CI. Configuração não muda o runtime dos serviços.

### 9.60 Imagens executáveis e startup — B07.1

**Objetivo/aceite:** empacotar os dois JARs do mesmo checkout em imagens independentes e observar startup real non-root, HTTP200/status UP com banco e Rabbit, duas exchanges produtoras direct/duráveis e consumidores zero com flags false. Não mudar domínio, contratos ou regra de FALHOU; configuração operacional usa exceção de red do AGENTS.md, com smoke real obrigatório antes de integrar. Docker local indisponível não é red de negócio.

**Baseline/necessidade:** runtime Java21 necessário à plataforma prevista no plano. Eclipse Temurin oficial21.0.12.1_1-jre-jammy, índice f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b e manifestamd64 8c2dddf1bb2a8455160f4e23080059de5003eddc5cb839130b177c6be0c2cfe0 verificados em2026-10-04. Versão/digest e fontes no runbook infra/images; primeira execução/download comprovada pelo Images CI #1 abaixo. Sem dependência Maven ou ferramenta instalada; PostgreSQL/Rabbit/Testcontainers mantêm baselines atuais.

**Decisão mínima:** Dockerfile por serviço copia JAR exato0.0.1-SNAPSHOT, USER10001:10001, ENTRYPOINT exec Java e CMD vazio; sem Maven/RUN/download no build derivado. Dockerignore permite somente Dockerfile/target/JAR exato, sem fontes/configuração local. Atualização da versão exige atualizar ambos caminhos, evitando wildcard que selecione artefato antigo. Workflow novo contents:read/actions fixadas/cache Maven/timeout15, prepara artefatos e constrói imagens locais com SHA do checkout, sem publicação registry. Os verifies completos/Flow permanecem; -DskipTests package apenas prepara o job dedicado.

**Smoke escrito:** ImagensCredPayE2E fora da descoberta padrão e explicitamente selecionado. Rejeita tag inválida e desabilita pull dos apps; bancos próprios/aliases internos/broker, credenciais fictícias explícitas, Rabbit health ligado/flags false. Espera HTTP200/JSON UP, confere id-u/Config.User/mounts efetivos vazios/privilegedfalse, exchanges e consumidores. Network primeiro no try, apps por último/start dentro: cleanup apps antes de infra/rede inclusive falha parcial. Não há mounts ou socket nos apps, imports Java do outro serviço, política consumidora ou transação financeira no smoke. Marcadores de evidência fixos sem dados/credenciais; não publicar inspect/env/logs integrais.

**Verificação local:** compile offline32 fontes de teste passou em11,957s; verify168 de transações/JAR com seletor sem infraestrutura !*E2E passou em37,598s, sem falhas/erros/skips e dinâmica impedida. Após revisão, resposta do broker exige array não nulo e dockerignore exclui filhos de target antes de reincluir JAR exato; recompilação32 passou em24,759s. UTF-8/diff/revisão passaram, sem bloqueantes para primeiro CI. Todos os cinco gates do primeiro SHA verdes; gates do commit documental final ainda necessários. Seletor local exclui ambos E2E, sem alterar descoberta padrão279/Flow4 ou reduzir gates. Build/run smoke real não foi executado localmente; Docker indisponível.

**Primeiro smoke real em `0040995`:** [Images CI #1](https://github.com/Joaomagh/credpay/actions/runs/37177360363), job111362666814, construiu as duas imagens a partir do checkout de merge da PR (`github.sha=1680889222c2e608e746c807533b99df77543b02`), com mesmo runtime/digest e dois JARs próprios. Contextos58,45/58,43MB; imagens locais sha256:6dc147d76e7b864a9ba1c2607a3e63731e2596fb7855fd0343e4b5eaaec1e03d e sha256:096891e95c8be032a2145199665615de1a6e6380fef83d9bd0bcaf9f002e0237. Smoke1/26,26s (Maven27,892s), falhas/erros/skips0: ambos UID10001/mounts0/privilegedfalse/healthUP, exchanges direct/duráveis/consumidores0. Sem self-attach/dynamic/CDS ou warnings de conexão fechada. Preparação Maven4,589s/3,071s apenas empacotou; verifies completos continuam obrigatórios. Digest identifica runtime/artefato observado, sem promessa de build byte-a-byte reproduzível ou publicação registry. CI real demonstrou startup; não executado localmente.

**Gates do primeiro SHA `0040995`:** processador #165/run37177360312 passou114/8m26s, transações #181/run37177360339 passou279/2m22s, Flow #34/run37177360462 passou4/2m07s, Images #1 e Scan #92/run37177360351 verdes. Sem skips/falhas/erros; self-attach/dynamic0, CDS1 em cada módulo. Pool1processador0 warnings, pools2/3/5 10/9/10; transações0. Contagem9 não prova correção do pool3. Triagem limitada do log Images #1 não encontrou os formatos pesquisados de chave privada/tokens GitHub/OpenAI/AWS/JSON financeiro; artifacts desse run total_count0. Não encerra auditoria histórica B08.1. Revisão final sem pendências; gates finais e3fb345: processador #166/run37250651514 passou114/8m17s, transações #182/run37250651521 passou279/2m40s, Flow #35/run37250651536 passou4/1m41s, Images #2/run37250651516 passou1/36,15s (Maven38,464s) e Scan #93/run37250651613 verde. Apps UID10001/mounts0/privilegedfalse/healthUP, exchanges/consumidores0 novamente; self-attach/dynamic0/CDS1 em módulos, warnings processador0/10/6/10 nos pools1/2/3/5 e transações0. PR #114 integrada em4d967b3 após revisão/gates; menor contagem no pool3 não significa correção.

**Limites/próximo:** esse incremento não prova POST→GET nas imagens nem provisiona políticas/consumidores; não conclui B07/Compose/Kubernetes. Base/daemon/runner permanecem confiados; bridge não é allowlist e não comprova AI-Jail. Atualizações/vulnerabilidades de imagem exigem incremento próprio; nenhum scanner novo ou destino externo presumido. Próximo slice B07.2 deve provar preparação opt-in nas imagens antes do fluxo financeiro.

### 9.61 Preparação opt-in e consumidores nas imagens — B07.2

**Objetivo/aceite:** ampliar o mesmo smoke para três fases mantendo dois bancos e broker. Primeiro flags false/exchanges produtoras/zero filas e consumidores; depois topologias true/listeners e publicação false, importar políticas existentes e conferir recursos efetivos sem consumo; somente então opt-in completo e dois consumidores exatos/ack obrigatório/prefetch10/1, bancos próprios. Não mudar regra de negócio/contrato/produção, imagem, baseline ou dependência. Caracterização/configuração operacional sem red artificial; controles desligado→preparado→ativo devem ser observados nos containers reais.

**Decisão:** apps novos em try interno por fase, registrados antes do start/cleanup reverso antes da próxima fase; infra/rede somente no try externo. Mesmo SHA/duas imagens locais com pull desativado, UID/mounts/privileged/health conferidos em todas as fases. Arquivos de políticas copiados somente para broker descartável, importados após declarações; quorum/durable/argumentos exatos/policy/operator vazia/definições efetivas/flag stream_queue/exchanges/bindings conferidos contra artefatos versionados. As definições incluem at-least-once, overflow reject-publish e caps10000/1000 já existentes. Não sobrescrever política de ambiente externo.

**Prontidão:** health precede listeners possíveis; polling do broker limitado10s exige dois consumidores das entradas, nenhum na DLQ, ack e prefetch atuais. JDBC somente lê schemas próprios/cross-schema ausente, sem resultado financeiro fabricado. Timeout existente5min/job15 preservado; baseline smoke anterior36,15s, medir três fases no CI antes de ajustar limite. Não encurtar verificações. Filtros Images agora incluem infra/rabbitmq/** porque o smoke usa os artefatos; módulo/processador/Flow e demais filtros permanecem.

**Verificação local:** compilação32 fontes offline passou em10,469s. Revisão/UTF-8/diff passaram sem bloqueantes; quatro gates iniciais verdes, repetir no SHA documental final. Processor verify114 não dispara por filtro se somente harness de transações/workflowImages/docs mudam; última evidência #166, produção intacta. WorkflowImages reempacota e executa o processador real; isso não substitui o verify quando o módulo for alterado. Sem Docker local, prova de execução via CI.

**Primeira prova em `d6026d4`:** [Images CI #4](https://github.com/Joaomagh/credpay/actions/runs/37251745556), job111580620216, passou um smoke48,23s (Maven49,917s), zero falhas/erros/skips. Marcadores das três fases: exchanges produtoras/filas0/consumidores0 → quatro filas quorum/políticas e bindings efetivos/consumidores0 → consumidores2/ack obrigatório/prefetch10/1/bancos próprios. Os seis startups confirmaram UID10001/mounts0/privilegedfalse/healthUP. Nenhum self-attach/dynamic/CDS/warning de conexão fechada no log do job; não inspeciona logs completos dos apps. Preparação Maven4,418s/3,139s só empacotou. Timeout5min/job15 preservado após medição; Todos os gates iniciais d6026d4 verdes: transações #184/run37251745573 passou279/2m41s, Flow #37/run37251745545 passou4/2m09s e Scan #95/run37251745546 verde, junto de Images #4. Logs: self-attach/dynamic0 em todos, CDS1 somente transações, warnings de conexão fechada0 nos três jobs inspecionados. Revisão final sem pendências; gates finais3085324: Images #5/run37252054661 passou1/65,38s (Maven1m07s), transações #185/run37252054587 passou279/2m13s, Flow #38/run37252054590 passou4/2m06s e Scan #96/run37252054644 verde. Seis UID/mounts/health e três fases novamente; logs self-attach/dynamic0, CDS1 somente transações, warnings de conexão fechada0. PR #115 integrada em e7388be após todos os gates/revisão, reviews/threads vazios.

**Limites/próximo:** não envia POST/evento financeiro e não afirma POST→GET nas imagens. Depois deste aceite, B07.3 cobre AP/REJ e replay pelas imagens, antes de Compose. FALHOU depende de regra Navigator, sem bloquear preparação atual; sem publicação externa, AI-Jail ou allowlist de egress.

### 9.62 Fluxo financeiro e replay do POST nas imagens — B07.3

**Objetivo/aceite:** caracterizar o fluxo já implementado nos dois JARs agora empacotados nas imagens, sem comportamento novo de produção, alteração de wiring/contrato/dependência ou red artificial de negócio. Após três fases conferidas, dois casos sequenciais50.000/150.000 BRL e limite100.00 devem nascer PENDENTE e alcançar GET APROVADA/REJEITADA. HTTP somente pelo app de transações; processador decide de fato, sem evento/resultado fabricado ou SQL de escrita.

**Prova escrita:** POST201/UUID/Location, GET final preservando identidade/valor/moeda, polling separado das duas outboxes publicadas. JDBC lê decisão/duas intenções/histórico e exige unicidade nas cinco tabelas, causa/correlação/eventId/outputEventId, resultado/limite/origem, epoch/nanos da entrada e saída, instante confirmado da decisão, decimal textual e escala3 nos dois bancos. Filas ready/unacked vazias e contagens presentes/válidas; nenhum fallback de campo ausente para zero.

**Replay:** snapshot completo dos cinco conjuntos persistidos somente após convergência/publicação/filas vazias. POST com mesma chave e decimal de escala equivalente conserva corpo e Location originais PENDENTE, GET final e snapshot integral; aguardar filas vazias antes do próximo caso/cleanup. Snapshots não são uma transação distribuída: ambiente isolado está sem novos produtores e publicações observadas já terminaram. Preserva as três fases/non-root/cleanup/schemas próprios; continua um smoke dedicado e dois casos dentro dele, sem inflar contagem para dois testes.

**Diagnóstico:** mensagens fixas em assert de JSON/map/valor/snapshot; parser JSON falha observavelmente omitindo conteúdo/cause que poderia incluir corpo. Marcadores imprimem somente estado, contagens e conclusão, sem IDs/valor/payload/credenciais. HTTP connect2s/request3s, GET20s/outboxes10s/filas10s, teste5min/job15 preservados e medir no CI. Não copiar recuperação ou republicação do harness JAR, nem criar framework.

**Documentação corrigida:** seção6 mantinha anotações antigas de consumidores planejados apesar dos fatos B04/B05 e dos gates reais. Corrigidas para consumo idempotente/ack após commit/estado e histórico atômicos, com referências às evidências; nenhum campo/semântica do contrato muda. Histórico dos incrementos anteriores permanece.

**Verificação:** compilação offline32 passou6,921s e8,213s após guards de escala/instante/contagens; revisão sem bloqueantes, UTF-8/diff verdes. Primeiro SHA791f8d3: [Images #7](https://github.com/Joaomagh/credpay/actions/runs/37253250486) smoke1/75,84s/Maven1m18 confirmou AP/REJ, duas publicações/histórico/replay em cada caso; [transações #187](https://github.com/Joaomagh/credpay/actions/runs/37253250532) verify279/2m38, [Flow #40](https://github.com/Joaomagh/credpay/actions/runs/37253250577) quatro casos/2m05 e Scan #98 verdes. Transações self/dynamic0, CDS1 visível, warnings Hikari fechada0; smoke/Flow self/dynamic/CDS0. Final92be78a: Images #8/run37253577427 smoke1/80,13s, transações #188/run37253577496 verify279/2m12, Flow #41/run37253577433 quatro casos/2m03 e Scan #99 verdes; mesmos marcadores/limites, revisão sem pendências. PR #116 integrada em37999c4. Docker local indisponível; prova financeira das imagens é do CI real. Processor verify114 permanece baseline#166, filtro não disparou/produção intacta. Próximo Compose depende da integração; FALHOU aguarda regra Navigator. Não comprova reentrega/reinício/DLQ recovery nas imagens (existem nos JARs), HA, Kubernetes, publicação externa ou sandbox/egress.

### 9.63 Ambiente Compose com preparação e persistência — B07.4

**Objetivo/aceite:** configuração operacional do mesmo fluxo, cinco serviços/três volumes próprios, preparo antes de opt-in e HTTP AP/REJ/replay. Down sem excluir volumes, novo preparo/ativação e GET/POST originais preservados. Sem main/dependência/contrato novo ou red artificial de negócio; execução real do roteiro obrigatório no CI. Docker local continua indisponível.

**Desenho:** mesmos digests Java/PG17.11/Rabbit4.3.5 e JARs do SHA, apps locais pull_policy never/UID10001/sem mounts/cap_drop ALL/no-new-privileges. Dois bancos separados, portas só HTTP/loopback; broker hostname rabbit/nodename rabbit@rabbit/volume próprio. Credenciais fictícias no .env.example, .env ignorada; inicialização de volume não atualiza usuário/senha por troca de env. Config resolvida/inspect ficam somente em memória, nunca impressos.

**Roteiro:** PowerShell existente, Compose stop somente apps antes de preparar infra; service_healthy e up --wait, health HTTP/JSON UP/UID/mounts. Flags false antes das exchanges; topologia true com consumidor/publicação false; políticas existentes ausentes/idênticas importadas, conflito/operator policy interrompe antes da importação. Conferir quatro filas/argumentos/políticas efetivas/flag/exchanges/bindings e consumidores zero; só então recriar apps com opt-in, exigir duas entradas/ack/prefetch10/1. Prepare/Activate/Smoke em falha tentam parar apps sem remover dados; erro de stop visível. Não apagar ou esconder conflito para ativar.

**Prova escrita:** Demo cria dois exemplos fictícios e confere POST201/PENDENTE/UUID/Location, GET final/id/valor/moeda e replay da resposta original. Smoke recusa projeto já existente, conserva três volumes em down/up e compara GET/replay dos mesmos dois registros. Prova SQL completa fica no Images smoke, falhas/reentrega nos quatro casos Flow; não duplicar matriz. Job15min, waits limitados e cleanup always somente do projeto CI exclusivo por run/attempt; não oferece remoção de volume local ou prune.

**Baseline/fonte:** PowerShell7.6.5 e Compose2.39.4-desktop.1 disponíveis localmente, config --quiet passou sem daemon; runner deve registrar versões antes de executar. Nenhuma instalação ou dependência adicionada. Curl vem do [runtime já aprovado](https://github.com/adoptium/containers/blob/47683eb1fa1b9576fe9d346f25e4158a5bbabf03/21/jre/ubuntu/jammy/Dockerfile), healthcheck real comprovado no Compose CI #2 abaixo. Referências [ordem/readiness](https://docs.docker.com/compose/how-tos/startup-order/), [services/pull_policy](https://docs.docker.com/reference/compose-file/services/) e [preservação no down](https://docs.docker.com/reference/cli/docker/compose/down/). Parser PowerShell sem erros, config --quiet e inspeção restrita cinco serviços/três mounts próprios/apps sem mounts, UTF-8/diff e revisão pré-CI sem bloqueantes. Revisão identificou list_policies.definition como texto JSON na [fonte Rabbit4.3.5](https://github.com/rabbitmq/rabbitmq-server/blob/v4.3.5/deps/rabbit/src/rabbit_policy.erl); somente esse campo é normalizado antes da comparação, definição efetiva da fila segue objeto. CI real confirmado abaixo; app non-root não comprova sandbox/egress. Não é prova de crash, HA ou implantação externa; próximo Kubernetes local/probes/observabilidade.

**Primeira execução/diagnóstico:** Compose CI #1/run37254691614, SHA695ce59, registrou PowerShell7.6.6/Compose2.38.2, packages/build verdes e falhou no JSON antes do primeiro marcador de apps; cleanup do projeto passou. Hipótese isolada com HTTP real loopback e mídia application/vnd.spring-boot.actuator.v3+json: Invoke-WebRequest retornou Byte[], conversão implícita string produziu números separados e parser falhou; decodificação UTF-8 conservou statusUP. Helper Json extraído sem alterar comportamento: controle check-json falhou na entrada byte pelo mesmo motivo, após texto válido; correção mínima aceita string ou decodifica bytes com UTF-8 estrito, controle ficou verde e rejeita JSON/UTF-8 inválidos com mensagem fixa. Sem payload de app exposto, sem truncar teste/timeout; prova Compose completa deve ser repetida. Controle passa a rodar no workflow; marca somente tipo CLR do health para confirmar fronteira real.

**Green real:** [Compose #2](https://github.com/Joaomagh/credpay/actions/runs/37255146642), SHA8891d32, executou controle JSON, duas sequências de três fases (12 startups/healthByte[]/UID/mounts), políticas exatas, dois consumidores/ack/prefetch, AP/REJ e replay. Roteiro167,922s até marcador final; down/up conservou três volumes e GET/POST dos mesmos dois registros, cleanup CI passou. Scan #102 verde, nenhum self/dynamic/CDS no job. Sem alteração de código/dependência dos serviços: suites/Images/Flow intactos na baseline PR #116, filtros não disparam aqui. Revisão do fix sem bloqueantes; documentos finais exigem CI/Scan do último SHA antes de integrar. Prova somente CI, sem reduzir limites ou suprimir erros.

**Gates finais e integração de B07.4:** [Compose #3](https://github.com/Joaomagh/credpay/actions/runs/37255526278), SHA `1e681c6`, repetiu o roteiro completo em 169,136 s; Scan #103 verde, revisão assistida sem pendência e zero reviews/threads pendentes. PR #117 integrada em `3e89218` após checks do SHA final. Docker Linux local continua indisponível; reprodução demonstrada no CI.

### 9.65 Ciclo de vida da fixture de outbox do processador — B08.2e

**Objetivo/aceite:** a fixture PublicarOutboxProcessamento deve fechar todos os pools capturados antes de parar seu PostgreSQL, conservando os quatro cenários existentes de health/publicação/recuperação/duplicata após falha de marcação. Baseline processador #156 registra dez warnings no pool 2; zero warnings isolado não comprova lifecycle correto.

**Red preparado:** captura de HikariDataSource antes do reset/SQL de cada método; SAME_THREAD conserva a lista. Subclasse local do container observa pools no stop real com PostgreSQL ainda ativo, exige fechamento e sempre chama super.stop em finally. Não fechar pools manualmente no guard nem adicionar DirtiesContext antes do red observado. Cenários, payload/eventId, filas em finally, scheduler PT1H, imagens, produção e workflow permanecem intactos. Compilação offline test-compile passou 23 fontes em 21,910 s; revisão assistida sem bloqueantes. Docker Linux local indisponível; executar verify padrão no CI e exigir falha exclusiva do teardown com quatro cenários verdes. Red ainda não observado; não é aceite.

**Frente pausada por autoridade:** proposta Kubernetes permanece na PR #118 em rascunho, workflow fora de .github/workflows e Scan #105 verde em 9a885cb. Exceção específica para node e componentes internos padrão no CI foi solicitada ao Navigator; nenhuma execução privilegiada autorizada. B08.2e é o único incremento de implementação ativo enquanto essa decisão está pendente.

**Red observado:** [processador #168](https://github.com/Joaomagh/credpay/actions/runs/37257752728), SHA `0dce46e`, quatro capturas do HikariPool-2 com closed=false/postgresRunning=true; falha exclusiva no teardown exigindo pools fechados antes do PostgreSQL. A classe agrega quatro métodos verdes e uma falha de callback (5/Failures1/Errors0); o resumo Surefire agrega 115 execuções, 114 cenários verdes e um erro de callback, zero skips. Verify terminou em 8m12s; dez warnings próprios e dez em cada pool 3/5, sem esconder os demais. Imagens #10 passou smoke 1/74,59 s; Flow #43 passou quatro casos/2m02s, Compose #5 confirmou persistência e Scan #106 verde no mesmo SHA. Após observar esse red, adicionada somente DirtiesContext AFTER_CLASS para fechar o contexto compartilhado depois dos quatro métodos e antes do stop. Green real ainda pendente.

**Green observado:** [processador #169](https://github.com/Joaomagh/credpay/actions/runs/37258595246), SHA `e39d453`, passou 114 testes/JAR em 8m12s, zero falhas/erros/skips. Shutdown completed do HikariPool-2 precedeu as quatro referências closed=true/postgresRunning=true; quatro cenários da fixture verdes em 8,650 s. Zero warnings próprios versus dez no red; pools 3/5 ainda nove/dez, sem correção inferida deles. Self-attach/dynamic zero, CDS 1 visível. [Flow #44](https://github.com/Joaomagh/credpay/actions/runs/37258595251) passou quatro casos/1m56s; [Images #11](https://github.com/Joaomagh/credpay/actions/runs/37258595184) smoke 1/141,0 s; [Compose #6](https://github.com/Joaomagh/credpay/actions/runs/37258595220) conservou três volumes/GET final/POST original; Scan #107 verde. Compilação green offline 23 fontes/17,828 s, revisão assistida e diff/UTF-8 verdes. Sem mudança de negócio, dependência, contrato ou timeout; próximos gates devem ser do SHA final documental antes de integrar PR #119.

**Gates finais de B08.2e:** SHA `a08faf7`: processador #170 passou 114/JAR em 8m25s; shutdown precedeu quatro referências closed=true/PG ativo, zero warnings do pool 2, pools 3/5 dez cada e CDS 1. Images #12 passou smoke 1/70,60 s; Flow #45 passou quatro casos/2m06s; Compose #7 preservou dados/volumes; Scan #108 verde. Revisão assistida sem bloqueantes e reviews/threads vazios na conferência. Em 2026-10-08, API pública confirmou cinco checks verdes e PR #119 ainda aberta/em rascunho. Conector GitHub de escrita não disponível nesta execução e gh ausente; não contornar gates via push direto em main. Integração pendente; B08.4 usa branch separada sobre esse SHA validado, não pressupõe merge.

**Integração:** após autenticação no navegador interno em 2026-10-08, cinco checks do SHA final e ausência de reviews/comentários de revisão reconferidos. PR #119 concluída em `d9a331f0896ebd0c4b7053a5e9bd5a7e667eae73`, com mensagem de merge descritiva. Nenhuma proteção ou falha de CI contornada.

### 9.66 Gate mínimo Checkstyle — B08.4

**Necessidade/baseline:** plano §4 prevê Checkstyle e ainda não havia gate automático. Baseline proposta já registrada em B08.4 antes da resolução: plugin Maven 3.6.0 e engine 14.3.0 explícito, Java 21. Fontes oficiais e comandos no [runbook](config/checkstyle/README.md). Ferramentas apenas de build, sem alteração de dependências de produção. Engine declara runtime Java 21 e sintaxe até Java 25; configuração verifica somente Java principal/testes, exclui resources, executa em validate. Regras: IllegalImport internos JDK, AvoidStarImport, UnusedImports, FileTabCharacter e NewlineAtEndOfFile; LF/CRLF, sem reformatação geral/supressões.

**Verificação:** validate passou com zero violações nos dois módulos (processador 7,980 s/transações 4,091 s). Sete controles isolados passaram: record/text block/pattern switch Java 21 válidos em LF/CRLF; cinco violações rejeitadas com diagnóstico específico. Roteiro parametrizado por módulo copia seu plugin real para POM temporário em .local; nenhum arquivo Java de produção/teste alterado neste incremento. Primeira execução do controle falhou antes do checker porque OuterXml inseriu xmlns no plugin, rejeitado pelo parser Maven. Removido somente o namespace redundante no fragmento que herda o namespace do projeto; controles repetidos verdes. Falha de POM não foi usada como red de regra/negócio. Configuração operacional não exige red artificial de negócio.

**Regressão local:** verify offline com os seletores sem infraestrutura documentados passou 75 testes/JAR no processador (20,406 s) e 168/JAR em transações (26,048 s), zero falhas/erros/skips e zero violações. Ambos JARs têm zero entradas Checkstyle/maven-checkstyle em BOOT-INF/lib. Revisão assistida sem bloqueantes; parser/UTF-8/diff verdes. Aviso CDS permanece visível uma vez por módulo, sem self-attach/dynamic. Suíte completa com PostgreSQL/RabbitMQ e CI do incremento ainda pendentes; Docker local não substituído por skips automáticos. Workflows dos dois módulos incluem os controles e filtros config/checkstyle, mantendo verify/timeout. Sem análise de vulnerabilidades concluída ou avanço antecipado no percentual global.

**Publicação:** PR #120 criada em 2026-10-08 no SHA cb354e8, branch `chore/b08-4-checkstyle`, com diferença exclusiva do incremento contra main após integrar PR #119. Seis workflows iniciados (transações #190, processador #172, Flow #47, Images #14, Compose #9 e Scan #110). Resultados/integração ainda pendentes. Branch remota antiga preservada, sem exclusão ou reescrita.

**Falha operacional do CI — 2026-10-08:** PR #120 (`chore/b08-4-checkstyle`), processador #172 e transações #190 rejeitaram a etapa de controles, embora os sete markers estivessem verdes. Shell pwsh padrão propaga LASTEXITCODE do último Maven negativo esperado ([contrato oficial](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax)). Reprodutor local com o mesmo epílogo do shell confirmou sete controles corretos/código 1 antes da mudança. Acrescentado exit 0 exclusivamente depois de todos os critérios: green local sete controles/código 0. Throw continua abortando em falha inesperada. Revisão sem bloqueantes; sem alteração de negócio/Java, não red artificial. CI corrigido ainda precisa executar. PR #119 já integrada em d9a331f; autenticação restabelecida. Roteiro e preferência de apresentação estão na PR #121 separada.

### 9.67 Roteiro de demonstração e apresentação — B08.5

Em 2026-10-08, [docs/DEMO.md](docs/DEMO.md) reúne preparo/ativação do Compose, fluxo fictício, recuperação no broker descartável, explicação de aproximadamente três minutos e seis perguntas com referências de código/evidências. README corrige o roadmap para os experimentos e entregas já comprovados. Não altera comportamento, dependências ou infraestrutura; não executa a demo local nem comprova o aprendizado de João. Engine local, observabilidade, Kubernetes, sandbox e regra FALHOU mantêm seus limites documentados. Percentual global segue estimado em 65% concluído/35% restante.

Preferência de João registrada em AGENTS.md: nomes e textos públicos centrados no projeto, sem inclusão espontânea de marcas de ferramentas de IA; novas branches descritivas. Branch local renomeada para `docs/b08-5-demo-closeout`. Histórico e referências de branches existentes preservados. A preferência aplica-se a chats que leiam o repositório, sem promessa de memória global.

Verificação documental: referências locais/UTF-8 passaram; git diff --check sem erros (aviso de normalização LF/CRLF do checkout). Revisão documental sem bloqueantes; precisão ajustada para atribuir persistência após down/up ao Compose. Sem alteração de runtime, não exige red artificial ou repetição de suítes de negócio. Gates de CI/integração de B08.4 e PR #119 continuam independentes; não presumir integração por escrever o roteiro.

### 9.68 Inventário de vulnerabilidades dos apps — B08.6a

**Objetivo/baseline:** análise prevista no plano §4. Baseline registrada antes de resolver Trivy 0.75.0 Linux amd64/checksum e upload-artifact fixado; fontes e escopo no [runbook](infra/security/README.md). Workflow empacota dois JARs Java 21 do mesmo checkout, constrói imagens existentes e registra SHA/hashes/IDs/arquitetura. Scanner lê tars, sem socket. Download explícito das duas bases seguido de scans sem atualização durante os quatro alvos; versões/datas registradas não congelam bases entre execuções. Rede CI não comprova allowlist/sandbox.

**Controles locais:** `./infra/security/check-reports.ps1` passou nove controles: inventário válido preserva quatro alvos/metadados/contagens e exclui ambiente; vazio, biblioteca ausente, OS ausente, base ausente, SHA inválido, imagem inválida, relatório de imagem trocado e severidade desconhecida são rejeitados antes da publicação. Primeiro comando interrompido pelo parser PowerShell devido a interpolação de variável antes de dois-pontos; delimitador corrigido e controles repetidos com código 0. Configuração operacional sem red artificial de negócio. CI real e revisão ainda pendentes; não atribuir às fixtures cobertura dos JARs reais.

**Publicação/limites:** somente campos selecionados de pacotes/achados e manifestos; sem tars, env, histórico da imagem ou JSON bruto. Artefatos por sete dias. Achados HIGH/CRITICAL são exibidos para triagem, sem ignore-unfixed/supressões; inventário não é aprovação de segurança. B08.6b trata aplicabilidade, correção e política de bloqueio. Escopo exclui PostgreSQL/RabbitMQ/build/Kubernetes. Docker local indisponível; nenhum comportamento Java alterado.

**Primeiro CI:** Inventory #1/run37842648059 falhou por relatório transacoes-jar vazio; quatro processos retornaram 0, mas o validador interrompeu antes do upload. Imagens reconheceram Java/143 pacotes Ubuntu, enquanto o comando fs encontrou zero arquivos Java. Fonte oficial Trivy0.75.0 pkg/commands/artifact/run.go confirma que ScanFilesystem desabilita TypeIndividualPkgs; para JAR empacotado usar rootfs no diretório isolado contendo somente app.jar, preservando checks de cobertura. Não reduzir o aceite. Correção/revisão/novo CI pendentes.
**CI corrigido:** Inventory #2/run37843133113 e Secret Scan #119/run37843132953 verdes no head e947fb7. Checkout de merge 3ae9d97b: 71 pacotes por JAR/214 por imagem (143 OS); nove controles Linux passaram, quatro relatórios íntegros publicados/6 arquivos/11.4 KB. Cada JAR: LOW1/MEDIUM12/HIGH10/CRITICAL5; cada imagem: LOW22/MEDIUM27/HIGH11/CRITICAL5; UNKNOWN0. Não somar como achados únicos. Triagem inicial e ações pendentes em infra/security/TRIAGE.md; segurança não aprovada. Revisão final/integração pendentes.

**Integrações anteriores confirmadas em 2026-10-08:** B08.4 PR #120 merge `3c8888d`, seis checks verdes no SHA `32ccb91` (Processing #174, Transactions #192, Flow #49, Images #16, Compose #11, Scan #113). B08.5 PR #121 merge `307d160`, Scan #115 verde em `0d6b537`, revisão documental concluída. Ensaio de João permanece pendente; estimativa global 65% concluído/35% restante.

### 9.69 Atualização compatível do Tomcat — B08.6b.1

Baseline anterior: Inventory #3, três CRITICAL Tomcat10.1.55 (CVE-2026-65182/65905/68525). [Apache](https://tomcat.apache.org/security-10) informa que10.1.58 não foi publicada após votação;10.1.59 inclui aquelas correções e10.1.60 correções posteriores. Maven Central confirmou10.1.60 e Boot3.5.16 como último3.5 antes da resolução. Necessidade/baseline registrada em backlog antes de baixar dependências. Propriedade tomcat.version10.1.60 nos dois POMs preserva gerenciamento do parent, Boot3/Java21 e linha10.1; sem dependência/abstração nova ou alteração de negócio.

Verify local com seletores sem infraestrutura (comandos na seção11) passou transações168/1m09s e processador75/1m02s, zero falhas/erros/skips. Ambos JARs contêm somente core/EL/WebSocket10.1.60 alinhados. Logs ignorados em .local/evidence/b086b-*-verify.log; Checkstyle passou e CDS permanece visível. Configuração de dependências sem red artificial de negócio; inventário anterior é baseline de achados, não um teste de exploração. As sete verificações do código a7972fb passaram, incluindo as integrações reais; a indisponibilidade do engine local não reduziu os aceites.

**Scan da correção:** Inventory #5/run37844620033 verde em a7972fb (checkout de merge0a746a28): cobertura preservada71/JAR214/imagem, CRITICAL5→2 em cada alvo, HIGH10/JAR11/imagem mantidos. Os dois CRITICAL restantes no JAR de transações são Spring47884/47890; nenhum Tomcat CRITICAL na saída selecionada. Flow #51, Images #18, Compose #13 e Scan #122 verdes; Transações #194 e Processador #176 também verdes, concluindo as sete verificações do código. Revisão final sem bloqueante; integração aguarda checks dos registros documentais. Artefato selecionado sha2562192b082aba574d994e587aed7a40578ee339115d31298483485bee9432a5a79. Aviso CI: ubuntu-latest migrará para Ubuntu26 a partir de19/10; não é erro ou baseline fixa do runner.

B08.6a integrada na PR #122/8007dfa após Inventory #3/run37843707080 e Scan #120/run37843707085 verdes em4beab86. Triagem parcial registrada, demais HIGH/CRITICAL preservados; sandbox, Kubernetes, FALHOU e ensaio pendentes.

### 9.70 Explicação do produto e direção FALHOU — B08.5b

João solicitou explicar o que é o CredPay, problemas resolvidos, escolhas e propósito antes de continuar. `docs/ENTENDA_O_CREDPAY.md` apresenta API/simulação fictícia, exemplo BRL50/150 com limite100 por transação (não saldo), fluxo assíncrono, falhas/soluções e tecnologias/motivos. README e demo apontam o documento. Estimativa ponderada permanece aproximadamente65%/35% com etapas do backlog; não conta PRs nem promete prazo. Documento revisado não comprova aprendizado: ensaio e execução por João continuam necessários.

Na resposta de 2026-10-08, João aprovou a direção recomendada: esgotamento de retry mantém PENDENTE recuperável; FALHOU é terminal após confirmação de falha técnica irrecuperável. Definir mecanismo/autoridade, condições/evidências e testes antes de implementar. Este incremento não adiciona endpoint, migration, estado ao enum ou transição de negócio; referências atuais deixam de chamar a direção de produto de pendente, registros históricos permanecem.

B08.6b.1 integrado na PR #123/175e139 após sete checks finais verdes em2cece9b: Transações #195, Processador #177, Flow #52, Images #19, Compose #14, Inventory #6 e Scan #123. Revisão final sem bloqueante. Redução observada e demais riscos em §9.69/triagem; nenhuma aprovação global de segurança.

Validação documental: UTF-8 estrito, links locais, git diff --check e revisão de fatos/limites. Sem novo comportamento/dependência, não exige red artificial nem reexecução das suítes pelos filtros da PR isolada; Secret Scan segue obrigatório antes de integrar.

### 9.71 Jackson compatível — B08.6b.2

Baseline registrada no backlog antes da resolução: Jackson core/databind 2.21.4 com cinco HIGH (CVE-2026-89407/89425/68497/91776/91777). Advisories FasterXML e BOM publicado conferidos; propriedade jackson-bom.version 2.21.7 nos dois POMs mantém Boot 3.5.16/Java 21 e a linha 2.21. Annotations 2.21 é a versão prevista no BOM; não forçar 2.21.7 nesse módulo. Sem alteração de Java, contratos, dependência nova de aplicação ou supressão. Revisão da proposta sem bloqueante.

Verify local com os seletores sem infraestrutura da seção 11 e -DargLine=-XX:-EnableDynamicAgentLoading passou: transações 168 testes/32,498s; processador 75/29,663s; zero falhas/erros/skips. Ambos JARs conferidos com ZipFile contêm core/databind, datatype-jdk8/jsr310, module-parameter-names e dataformat-toml 2.21.7, annotations 2.21; nenhuma outra versão Jackson empacotada. Checkstyle passou; um aviso CDS por módulo, zero avisos de agente dinâmico. Logs ignorados em .local/evidence/b086b-jackson-*-verify.log. Atualização operacional usa regressão existente, sem red artificial de negócio ou alegação de reprodução das cinco explorações.

Preparação inicial aguardou integrações PostgreSQL/RabbitMQ reais, Flow/Images/Compose/Scan e inventário comparando os cinco IDs nos quatro alvos. Docker local segue indisponível; não reduzir aceite ou encerrar a triagem geral. PR #124/5b105d5 integrada após Scan #126 verde no head 8f68a8f; explicação documental não comprova ensaio por João.

**Resultado final:** PR #125 integrada em 35c4087 após sete checks verdes do head07df2f0: Transações #197/279, Processador #179/114 (zero falhas/erros/skips), Flow #54, Images #21, Compose #16, Inventory #8 e Scan #128. Log completo do inventário run37868732324/job113621587872 confirmou ausência dos cinco IDs nos quatro scans reais, separados das fixtures sintéticas; cobertura71/JAR214/imagem, HIGH10→5/JAR11→6/imagem, CRITICAL2 por alvo. Checkout71919eb536cb0660fee953d0cc8c03d36f153a4d; artefato11589766080 SHA25660a39d12daed75edc1ddde9b5022e478d8c71f1be08d4e965cbb20a4bff54f61. Revisão sem bloqueante, sem supressão/segurança global aprovada.

Logs completos dos módulos: CDS1 cada, agente dinâmico0; transações sem warnings Hikari de validação. Processador #179 contém20 avisos de conexões fechadas (pool3=10/pool5=10), baseline #177 tinha18 (8/10), mesmos pools/mensagem/últimoRunning RabbitMqEntradaTopologyIntegrationTest. Variação2 observada, sem atribuição causal ao Jackson ou declaração de dívida resolvida; fixtures ainda exigem revisão própria.

### 9.72 Cliente RabbitMQ compatível — B08.6b.3

Baseline antes da resolução no backlog: quatro HIGH amqp-client5.25.0, candidata5.34.0 publicada no Central, linha5/Java21 e Spring AMQP3.2.12 preservados. Dois POMs usam rabbit-amqp-client.version5.34.0; sem contrato/evento/negócio novo, major ou supressão. Advisories e release oficiais justificam o recorte; não é alegação de latest ou reprodução dos ataques. Riscos negociação/frame/header e recuperação exigem regressões existentes com broker real.

Verify local com seletores da seção11: transações168/30,162s e processador75/26,668s, zero falhas/erros/skips; Checkstyle passou. Inspeção dos dois JARs: único amqp-client5.34.0, spring-amqp/spring-rabbit3.2.12. Logs ignorados .local/evidence/b086b-rabbit-*-verify.log; atualização operacional sem red artificial. CI completo, fluxo/images/Compose/Scan e inventário sem os quatro IDs nos quatro alvos ainda exigidos antes de integrar; engine local indisponível não reduz aceite.

**Achado e causa antes do merge:** Inventory #10/run37870136256 em de5f9af, checkout71cf36b7737bab4e638e38306f62eb28cb314e73, passou integridade mas introduziu Netty4.1.135.Final HIGH59901/CRITICAL75595. Quatro IDs Rabbit ausentes; contagens HIGH2/JAR3/imagem e CRITICAL3 por alvo. Cobertura79/222, maior que71/214: sete Netty JARs transitivos não opcionais + metadata jctools-core dentro de netty-common. POM publicado Rabbit pede4.2.16, mas parent gerencia4.1.135; não é variação da base nem nova regra de negócio. Artefato11590230920, SHA256c9c611a60288510c0be4d4c6aca27cecfa26a474ea159c87bc5127130e4b23c3. Merge bloqueado e baseline transitiva registrada antes de resolver4.1.137.Final. Fonte vendor não sustenta exclusão das bibliotecas: ConnectionFactory instancia NettyConfiguration e referencia tipos. Propriedade netty.version4.1.137.Final preserva linha gerenciada; nova regressão/scan exigidos. Nenhuma nova biblioteca deve entrar sem inspeção do POM transitivo: disponibilidade e versão direta não bastam.

**Correção transitiva local:** verify repetido após netty.version4.1.137.Final: transações168/36,307s, processador75/31,822s, zero falhas/erros/skips, Checkstyle passou. Dois JARs contêm exatamente sete módulos Netty4.1.137.Final e amqp-client5.34.0, com Spring AMQP3.2.12 preservado. Logs .local/evidence/b086b-rabbit-netty-*-verify.log ignorados. Nova rodada CI/inventário dos seis IDs permanece necessária; não usar a rodada de de5f9af como aceite do código corrigido.

**Aceite final Rabbit/Netty:** PR #126 integrada em b5e001f após sete gates verdes do head5f5e406: Trans200, Proc182, Flow57, Images24, Compose19, Scan131 e Inventory11. Log completo Inventory11/run37870861033: seis IDs tratados ausentes nos quatro alvos, cobertura79/JAR222/imagem, HIGH1/JAR2/imagem e CRITICAL2/alvo. Checkout8cd08378767b8517ac3b841cf57129381aaea3cc; artefato11590002126, SHA25676ee5de81d6ce9f9690263642d39f268df9c73658fc271e8f95daf43d6359b31. Proc182:114 testes/zero falhas/erros/skips, CDS1/dynamic-agent0/Hikari18; baseline177=18 e Jackson179=20, variação registrada sem atribuir causalidade. Dívida Hikari permanece; nenhum logger reduzido. Preparações acima são históricas, não bloqueio atual deste recorte. Restam JDBC, OpenSSL e Spring; sem aprovação global de segurança.

### 9.73 PostgreSQL JDBC compatível — B08.6b.4

Baseline e fontes no backlog antes da resolução:42.7.11→42.7.12, propriedade postgresql.version nos dois POMs, sem novas dependências declaradas no POM publicado. Correção de CVE-2026-54291 sob channelBinding=require; configuração não encontrada nos diretórios consultados. Não se alega exploração reproduzida ou teste TLS específico. Riscos de persistência exigem suítes reais/migrations/constraints/rollback/locks/snapshots e fluxo, além dos quatro relatórios sem o ID e cobertura79/222. Alteração operacional, sem red artificial; CI/inventário ainda pendentes.

Verify local pelos seletores da seção11: transações168/28,824s e processador75/25,107s, zero falhas/erros/skips, Checkstyle aprovado. ZipFile dos dois JARs confirma único postgresql42.7.12, amqp-client5.34.0 e sete Netty4.1.137.Final preservados. Logs ignorados .local/evidence/b086b-jdbc-*-verify.log. Engine Docker local indisponível; testes reais e sete checks remotos permanecem obrigatórios antes do merge.

**Aceite final JDBC:** PR #127 integrada em f342d3f, sete gates verdes em96f6f7a: Trans202, Proc184, Flow59, Images26, Compose21, Scan133 e Inventory13. Logs integrais:279/114 testes, zero falhas/erros/skips; CDS1 por módulo, dynamic-agent0, Hikari0trans/18proc (dívida preexistente). Inventory13/run37871930354 completo:54291 ausente nos quatro alvos, cobertura79/222, HIGH0/JAR1/imagem e CRITICAL2/alvo. Checkoutf392da356f712449d85b068783dc33791931e80d; artefato11590478677, SHA256aec3af8eacb8eda0f7c4d7e295b06886019cccc5d574a427b5c6542ffe82186a. Retenção sete dias; hash/identidade registram a evidência, não garantem disponibilidade futura. Preparação acima é histórica; JDBC encerrado, segurança global não aprovada.

### 9.74 Triagem restante e proposta de publicação — B08.6b.5

Em2026-10-09, consolidação documental em infra/security/TRIAGE.md separa os três IDs restantes, condições, fontes e ações. Inspeção de produção: único controller de aplicação é TransacaoController, REST/ResponseEntity em POST/transacoes e GET/transacoes/{id}; nenhuma classe MVC/view/resolver/SSE indicada nas buscas. application.yml não configura resolver/view; processador expõe health. Isso é inspeção de código/configuração, não prova dinâmica de ausência de exploração. Não alterar severidades brutas ou interpretar os testes funcionais como teste de vulnerabilidade.

Política proposta: entrega externa exige HIGH/CRITICAL corrigidos ou disposição estreita explicitamente aprovada pelo Navigator; inventário válido não libera publicação. Não cria gate automatizado, exceção, ignorefile ou autorização de implantação. Migração Boot/Framework major, suporte pago, outra família runtime e aceite de risco continuam decisões específicas de João. Próximo experimento pronto é B08.2f: capturar pool da fixture RegistrarProcessamentoOutbox, confirmar falha de fechamento antes do stop pelo guard real, só então corrigir o contexto e repetir os mesmos cenários/suíte. Associação entre nome de teste e warnings não prova propriedade do pool; sem diagnóstico causal não espalhar DirtiesContext.

Verificação documental: git diff --check passou; decodificação UTF-8 estrita e resolução dos links locais dos seis documentos passaram. Diff resumido: nota pública de risco, matriz de três achados, proposta de publicação e estados/próxima tarefa; nenhum POM, script ou workflow modificado neste incremento. Revisão documental detectou ID B08.2d já ocupado; novo experimento renomeado para B08.2f sem alterar o histórico. Correção verificada, restante sem bloqueantes; Secret Scan remoto ainda exigido antes da integração.

**Integração documental:** PR #128/7b6d5bf, headc2ee485, Scan135 e Inventory15/run37989265135 verdes. Log integral mantém quatro alvos79/222, HIGH0/JAR1/imagem CRITICAL2 e os mesmos três IDs. Artefato11645160323, checkout0a219a863b7ef5d5ee433a876464f6b4ac8ec7dd, SHA2565e7d23e7c6aff3087d696fb7284b8637bc377e63ac16aae0274ee8c09752f525. Nenhuma decisão de risco/política automatizada foi aprovada pela integração dos documentos.

### 9.75 Ciclo de vida do registro/outbox — B08.2f

Guard preparado antes da correção: captura HikariDataSource original antes de desarmar a falha em cada um dos dois cenários existentes; SAME_THREAD protege a observação. Subclasse PostgreSQLContainer observa o stop real com lista não vazia, banco ainda ativo e todos os pools fechados; super.stop sempre ocorre em finally. Cenários de replay e rollback/outbox permanecem intactos. Não há DirtiesContext novo neste red; causa só será confirmada pelo teste real.

Docker local indisponível exige red remoto, sem chamar falha de infraestrutura/compilação de red. Workflow processador inclui primeiro teste focado da classe, depois verify completo; mantém controles Checkstyle/Java21/argLine e timeout10min. Risco de tempo será medido; não aumentar limite ou reduzir suíte para passar. Sem dependência nova ou mudança de produção. Revisão, compilação e red real ainda pendentes; implementação aguarda falha esperada com dois casos de negócio verdes e guard fechado falso/PG ativo.

Revisão do guard sem bloqueantes para PR draft: red focado deve preservar os dois casos, duas capturas e PG ativo; verify114 obrigatório no green. Etapa focada é temporária para esta prova e será retirada após green, mantendo guard e repetindo suíte final. Compilação dos testes/verify local com seletor sem infraestrutura:75 cenários verdes, zero falhas/erros/skips, Checkstyle0,23,987s; log ignorado .local/evidence/b082f-red-local-verify.log. Isso valida estrutura, não executa o guard nem é red. git diff --check passou; nenhuma correção de fechamento foi aplicada.

**Red observado:** Processador186/run37990026316 em233820d, teste focado real. Dois casos de negócio passaram; duas capturas de HikariPool-1 closed=false/postgresRunning=true e única falha "todos os pools do registro/outbox devem fechar antes do PostgreSQL" no teardown. Surefire agrega3 execuções/1 erro de callback/zero skips; classe mostra1failure, agregado Errors1. Total19,415s, classe14,68s. verify completo não executou após red do foco, conforme ordem normal do job; não se alegam114 verdes nesta rodada. Causa comprovada na fixture: pool/contexto sobrevivia ao container; numeração1 do foco não atribui automaticamente os warnings históricos aos pools3/5 da suíte.

**Green mínimo preparado após red:** DirtiesContext(AFTER_CLASS) fecha contexto ao fim dos dois cenários e antes do stop, sem fechar pool manualmente, logger reduzido ou mudança de aplicação. Capturas e guard permanecem. Foco deve passar com duas referências fechadas/PG ativo, depois verify114 e gates do SHA atual; resultado remoto ainda pendente. Verify local75/zero falhas/erros/skips e Checkstyle0 em18,725s; revisão do green mínimo sem bloqueantes para push, sem aceitar merge antecipado.

**Bloqueio remoto confirmado, 2026-10-09:** no head90b9f42, Proc187/run37990341158 falhou ao baixar PostgreSQL (22 ocorrências de `toomanyrequests`), sem observações do guard ou execução dos dois casos; verify114 não iniciou. Flow62/run37990341115 falhou ao baixar RabbitMQ com o mesmo limite (22 ocorrências), antes do fluxo real. Ambos tiveram um erro de inicialização, não red/green de comportamento. Images29/run37990340973, Compose24/run37990341076 e Inventory18/run37990341091 falharam com HTTP429 ao resolver o digest Temurin já fixado. Uma única reexecução de Images29 no mesmo SHA (attempt2/job114025561849) repetiu429. Scan138 passou; nenhum inventário novo completo foi gerado. Não alterar imagens, timeout, suíte ou requisitos para contornar falha.

**Próxima decisão:** proposta concreta de autenticação Read Docker Hub preparada em arquivo local ignorado, não aplicada. Action oficial docker/login-action v4.6.0, SHA dbcb813823bdd20940b903addbd779551569679f, em seis workflows, permissões GitHub contents:read preservadas; sem push de imagem. Credencial e alcance exigem direção específica de João (AGENTS §1/§5). Secrets ausentes/forks/Dependabot mantêm pulls públicos e testes obrigatórios. Código confiável do job pode acessar o token; mascaramento não é isolamento. Nenhum token lido, criado ou cadastrado. Alternativa: aguardar recuperação da cota. PR #129 permanece draft; etapa focada só será retirada após green real e suíte114, repetindo gates finais.

### 9.76 Autenticação de pulls na CI — autorização e baseline

João autorizou a proposta de autenticação Read Docker Hub em 2026-10-09. Necessidade concreta: limite de pulls públicos impediu PostgreSQL/RabbitMQ e builds no head90b9f42. Baseline antes da execução: [docker/login-action v4.6.0](https://github.com/docker/login-action/releases/tag/v4.6.0), SHA oficial dbcb813823bdd20940b903addbd779551569679f, conforme tag conferida; [PAT](https://docs.docker.com/security/access-tokens/) Read com validade definida, sem senha/Write/Delete. Nenhuma instalação local ou publicação de imagem.

Configuração adicionada nos seis workflows de transações, processamento, fluxo, imagens, Compose e inventário. Antes do primeiro pull, verificar disponibilidade dos secrets sem imprimir valores e autenticar somente execução confiável do próprio repositório; forks e Dependabot não recebem login. Secrets ausentes mantêm pulls públicos e testes obrigatórios, com diagnóstico fixo. Actions fixadas, contents:read, imagens/digests/timeouts/testes preservados. Logout pós-job padrão; código confiável do job pode acessar o token durante execução, mascaramento não isola credenciais. Autenticação não garante cota ilimitada. Configuração operacional dispensa red artificial; revisão estrutural e CI real continuam obrigatórias.

Cadastro sensível fica com João, diretamente nos secrets Actions do repositório: CREDPAY_DOCKERHUB_USERNAME e CREDPAY_DOCKERHUB_TOKEN. Read pode alcançar outros repositórios acessíveis à identidade; revisar alcance/identidade e expiração. Nenhum valor foi solicitado no chat, lido ou cadastrado. PR129 permanece draft até foco/verify114 e demais gates reais; configuração aplicada ainda não equivale a login ou green comprovado.

**Green real após cadastro por João:** João confirmou revogação/substituição da credencial exposta e cadastro dos dois secrets; valores não foram acessados. Reexecuções attempt2 do headc2bd034: Proc188/run37992819760/job114045773895 passou foco2/26,722s e verify114/8m05, zero falhas/erros/skips. Foco: duas capturas HikariPool-1 closed=true/PG ativo; suíte: shutdown HikariPool-3 completo, duas capturas closed=true/PG ativo e zero warnings desse pool. Permanecem dez warnings HikariPool-5; não atribuir solução a outra fixture. Login bem-sucedido e logout pós-job. Trans204/run37992819828/job114046044107 passou279/2m47, zero falhas/erros/skips, zero warnings de conexão ou agente dinâmico. Flow63, Images30, Compose25, Inventory19 e Scan139 também verdes.

Inventory19 completo manteve quatro relatórios79/222, HIGH0/JAR1/imagem e CRITICAL2; mesmos três IDs47884/47890/84782. Checkout de merge5ce1603ee3aeaba9573dd06f2a4f15773defbcdb, artifact11648160553, digest06a5422cb98876d2ad78f734e349e907a0eab0b0009f8198b97b391242a7bd2b. Não é aprovação global de segurança. Após prova real, etapa focada temporária retirada; guard permanece na suíte. Comentário nos seis workflows explicita confiança no código do job. Exigir revisão e gates finais do novo head antes do merge.

**Integração final B08.2f:** head032daec teve sete gates verdes: Trans205/run37998100157, Proc189/run37998100277, Flow64/run37998100110, Images31/run37998100260, Compose26/run37998100144, Inventory20/run37998100421 e Scan140/run37998100105. Proc189/job114049170609 passou114/8m14/zero falhas/erros/skips; shutdown pool3 seguido de duas capturas closed=true/PG ativo, zero warnings próprios; pool5 permanece com dez. Sem etapa focada duplicada, timeout10 preservado. Inventory20 completo manteve quatro relatórios79/222 e mesmos três IDs; checkoutda7ae96f50930b062df3b6b44e745738be192bf4, artifact11648002757, digestac57c74b9a8d6a7e8f4e472c12f22c432a39c4e54bcacc63e092673c063f1c78. Revisão sem bloqueantes, PR #129 integrada emccc61d2, main local atualizado por fast-forward.

Próximo recorte B08.2g revisado: única fixture ProcessamentoServiceApplicationTest, dois cenários health HTTP/replay e RANDOM_PORT preservados. Capturas Hikari BeforeEach, SAME_THREAD, guard no stop real e cleanup finally; red deve passar os casos e falhar exclusivamente por pool aberto. Só depois AFTER_CLASS fecha contexto/servidor após último teste; green exige duas referências fechadas/PG ativo, suíte114 e gates. Não inferir propriedade apenas por numeração ou último teste; implementação não iniciada.

### 9.77 Ciclo de vida da fixture da aplicação — B08.2g

Red preparado antes da correção: ProcessamentoServiceApplicationTest mantém RANDOM_PORT, health HTTP e replay com valor de escala equivalente, dois casos/contexto/container compartilhados. BeforeEach captura Hikari original; SAME_THREAD protege observação; guard no PostgreSQLContainer.stop exige capturas presentes, PG ainda ativo e pools fechados, sempre libera em finally. Nenhum AFTER_CLASS novo, fechamento manual, logger suprimido ou mudança de produção. Baseline final189 tinha dez warnings pool5; propriedade deve ser confirmada nesta execução, sem depender só da numeração.

Docker local indisponível: CI inclui temporariamente foco da classe antes do verify completo, autenticação Read já aprovada e timeout10 preservados. Red somente se ambos os casos passarem e teardown falhar por pool aberto/PG ativo; startup/compilação não valem red. Depois da prova, correção mínima e foco/suíte114/gates, retirada da etapa temporária e repetição final. Sem dependência nova; revisão e compilação local ainda em andamento.

**B08.2g red confirmado:** Proc191/run37999741198/job114054626572 em a6af9f0, foco21,389s. Dois casos health HTTP/replay passaram; duas capturas HikariPool-1 closed=false/postgresRunning=true e única falha do guard no teardown. Surefire classe3/Failures1; agregado3/Errors1/zero skips representa callback, não terceiro caso de negócio. Verify114 não iniciou após red focado. Causa demonstrada na fixture: contexto/pool sobrevivia ao PostgreSQL. Só depois foi adicionado DirtiesContext(AFTER_CLASS), fechamento do contexto/servidor após os dois casos, sem fechamento manual ou redução de logger. Guard/casos permanecem; foco verde/verify114/gates ainda exigidos.

**B08.2g green real:** Proc192/run37999974389/job114055397073 em0448574 passou foco2/24,223s e verify114/7m48, zero falhas/erros/skips. Log completo: graceful shutdown do Tomcat e shutdown Hikari antes de duas capturas fechadas/PG ativo no foco(pool1) e na suíte(pool5). Zero warnings de conexão na suíte inteira, contra dez no baseline189; zero warning de agente dinâmico. Não houve redução de logger. Flow67/Images34/Compose29/Inventory23/Scan143 verdes nesse código. Inventory23 manteve quatro relatórios79/222 e mesmos três IDs; checkoutb2377131aec56cf558d821e7ad424d48fc689807, artifact11647624827, digest409268f5346dd37a6405b16000a66bbb3acb2d4f1f3910cf8f45d6db5064df86. Etapa temporária retirada após prova, guard permanece na suíte normal; exigir revisão e CI final do workflow alterado antes da integração.

**B08.2g integração final:** head8d29b3f teve seis checks verdes, confirmados na PR: Proc193/run38000883077, Flow68/run38000883083, Images35/run38000883097, Compose30/run38000883157, Inventory24/run38000883113 e Scan144/run38000883164. Proc193/job114058373312 passou114/8m08/zero falhas/erros/skips; shutdown gracioso Tomcat e pool5 completo antes de duas capturas closed=true/PG ativo; zero warnings de conexão e de agente dinâmico. Foco temporário ausente, guard permanente, timeout10 intacto. Inventory24 completo manteve quatro relatórios79/222 e mesmos três IDs; checkouta413b786e6e5ba23ee83d0e46c766266bc6f3735, artifact11649945610, digestbd2905aa80424c675733d45637864de8978b25a98a339e6babec9a88a1ee494a. Consulta pública GitHub atingiu limite; confirmação final pela página da PR, sem leitura de credenciais ou redução de checks. Revisão sem bloqueantes, PR #130 integrada em4f3ed7f, main local atualizado por fast-forward.

### 9.78 Contador de tentativas de publicação — B06.3

Baseline: Actuator existente inclui Micrometer core/observation/commons/jakarta9 1.15.12 no JAR local; nenhum POM, download ou instalação nova. Métrica proposta credpay.messaging.publish.attempts com tag outcome limitada a confirmed/returned/nacked/error; tentativas ao broker incluem reenvio e não significam transação única nem marcação da outbox. Não registrar IDs, payload, moeda ou erro em tags. Propriedades, timeout e comportamento de retorno/erros devem permanecer.

Preparação de teste: constructor recebe MeterRegistry, sem contagem antecipada; somente scaffolding de injeção para observar efeito, antes do primeiro red. SimpleMeterRegistry dessa versão possui close mas não implementa AutoCloseable: primeira tentativa com try-with-resources falhou na compilação, não contou como red; corrigida para finally/close. Teste confirmed então compilou, publicação retornou true e falhou exclusivamente por contador ausente (1failure/9,986s). Só depois foi acrescentada contagem mínima; demais outcomes aguardam seus próprios reds.

**TDD e prova real:** confirmed passou 1/11,089s após red. Returned falhou por contador ausente (2 testes/1 falha/10,370s). Na PR #131, Proc195/run38002997490, head f990832, executou os quatro cenários reais e falhou somente na classificação sem rota: confirmed esperado 1, observado 2 (26,297s), sem erros/skips. Quatro capturas de pool fechado com PostgreSQL ativo; nenhum warning de conexão. Só depois foi corrigida a precedência do retorno; foco confirmed/returned passou 2/12,039s.

Reds focados adicionais falharam exclusivamente pelo contador ausente: nack 1/12,835s; erro imediato de envio 1/10,935s; confirmação excepcional 1/11,070s; interrupção 1/13,126s. Após cada implementação mínima, testes acumulados passaram 3/11,987s, 4/12,158s, 5/12,511s e 6/12,108s. Envio entrou no bloco observado; RuntimeException original é propagada, causa da confirmação e flag de interrupção preservadas. Classificação/contagem após o bloco evita tratar uma falha do próprio contador como segundo resultado de envio. Teste adicional cobre o timeout existente de cinco segundos sem alterar o prazo; confirmação excepcional e timeout compartilham o tratamento já corrigido, sem alegar novo red para esse teste.

Fixture real compara deltas de confirmed/returned para envio roteado, ociosidade, ausência/restauração de rota e falha de marcação após confirmação seguida de reenvio. Esse último cenário deve contar duas confirmações, mesmo para o mesmo evento. Sem reset global, novo caso/container ou mudança de contrato. Foco temporário da fixture precede verify completo, timeout10 preservado; green real e gates ainda pendentes. PR permanece em rascunho.

`B06.3 local`: verify com exclusões explícitas de integração/HTTP passou 82 testes, zero falhas/erros/skips, 26,338s e Checkstyle0; sete testes do publicador incluem timeout real (classe5,488s). Não substitui PostgreSQL/RabbitMQ na CI. Revisão somente leitura sem bloqueantes; aceite remoto pendente.

Green remoto em 7820757: Proc196/run38003456323 passou foco4/32,294s e verify121/7m49, zero falhas/erros/skips. Guard da outbox observou quatro referências ao pool fechado com PG ativo em cada execução; zero warnings Hikari/agente dinâmico na saída completa. Etapa de foco retirada após essa prova; testes permanecem na suíte normal e head final ainda exige gates/revisão.

**Entrega final:** PR #131 integrada em 668d73b após seis checks verdes no head bbf99f296cf0874287efc81d6d04f6335cb67104. Run38014774384 passou verify121/8m13, zero falhas/erros/skips e warnings Hikari/agente dinâmico; fixture real4/9,647s, quatro referências ao pool fechado com PG ativo. Foco temporário ausente do workflow final. Inventário run38014774427: quatro relatórios completos79/222, mesmos três IDs Spring/OpenSSL; artifact11655609437, checkout8462b72bb3e386f953259556aa1a380dd4a04804. Revisão sem bloqueantes, main sincronizada por fast-forward. Screenshot ignorado .local/evidence/pr131-merged.jpg.

### 9.79 Diagnóstico da publicação de TransacaoCriada — B06.4

Próximo recorte aprovado pelo plano e revisado: completar a instrumentação no publicador do serviço de transações, com o mesmo nome e outcomes fixos de B06.3. Cada aplicativo tem seu próprio registry; contador não representa transações únicas ou conclusão de negócio. Baseline local do JAR de transações comprova Micrometer core/observation/commons/jakarta9 1.15.12 já existentes. Nenhum POM, download, instalação, módulo comum ou exposição HTTP nova.

Arquivos previstos: RabbitMqPublicadorEvento, seus três testes atuais, PublicarOutboxIntegrationTest e registros. Preparar injeção do registry sem contagem, confirmar red pelo contador ausente nos casos de confirmação/retorno/nack, corrigir cada resultado e então cobrir erros/interrupção/timeout. Preservar propriedades e cinco segundos, exceções e flag da thread. Fixture real já tem dois casos: confirmação e ociosidade; retorno sem rota e recuperação do mesmo evento. Publisher automático desligado permite deltas sem reset global; manter limpeza e guard Hikari. Ela não comprova falha de marcação após ACK. Exigir green real, suíte completa e gates do último SHA antes de integrar. TDD em execução, sem entrega integrada.

TDD iniciado: registry injetado como scaffolding sem contagem; os três construtores de teste foram ajustados antes do red. Caso existente de contrato confirmou retorno/propriedades corretos e falhou somente pelo contador confirmed ausente (1 falha/10,787s). Contagem mínima acrescentada depois; três testes verdes/8,963s. Asserção returned então falhou pelo contador ausente (3 testes/1 falha/8,455s), mantendo retorno false. Fixture real de dois casos ganhou deltas roteado/ocioso/sem rota/restauração, sem reset do registry ou infraestrutura nova. Foco temporário no workflow precede verify; timeout10 preservado. Verificação estrutural skipTests passou/8,051s e Checkstyle0, sem alegar execução real. Nack também confirmou red por contador ausente (1 falha); classificação aguarda prova remota. Revisão sem bloqueantes para rascunho; asserções de ociosidade após recuperação acrescentadas. Red remoto ainda pendente; não integrar o rascunho.

Red real em989b442/run38015730783: dois cenários executados, uma única falha de classificação (confirmed esperado0, observado1), zero erros/skips, 36,694s. Duas referências ao pool fechado com PG ativo, zero warnings Hikari. Returned foi corrigido somente após prova: foco3/9,201s; nack red1/8,563s→green acumulado4/9,306s. Erro imediato red1/8,974s→foco2/9,634s, confirmação excepcional red1/8,430s→green5/9,119s, interrupção red1/8,611s→green6/9,084s. Erros preservam identidade/causa/flag; classificação fora da captura evita dupla contagem. Timeout adicional caracteriza o tratamento já corrigido, sem alegar outro red. Sete casos no publicador (três existentes reforçados e quatro novos).

A primeira seleção local omitiu as exclusões PostgresRuntimeTest/*E2E e incluiu três casos sem Docker/imagens preparadas: FluxoCredPayE2E/PostgresRuntimeTest falharam por Docker indisponível e ImagensCredPayE2E pela tag de imagem ausente. Os sete testes do publicador passaram; isso não é red de negócio. Com o comando local já documentado (§11), verify corrigido passou172/20,603s, zero falhas/erros/skips e Checkstyle0; timeout real na classe7/5,264s. Suíte real283 e gates ainda pendentes, sem redução do CI. Revisão sem bloqueantes.

Green remoto8c9290d/run38016119774: foco2/32,193s e verify283/2m37, zero falhas/erros/skips e warnings Hikari/agente dinâmico. Duas referências ao pool fechado com PG ativo no foco e na suíte. Seis checks verdes na PR #132. Etapa temporária retirada após prova; todos os testes permanentes, timeout10 preservado. Head final ainda requer gates/revisão.

Aceite final: PR #132 integrada em d77d7f6 após seis gates verdes do head ce2f393. Trans209/run38016553489: 283 testes/zero falhas/erros/skips, 2m54; fixture2/8,619s e duas referências pool fechado/PG ativo, Hikari0/dynamic0. Inventory33/run38016553500: quatro alvos79/JAR222/imagem, mesmos três IDs; checkoutd038b86a06c69185fcfef89829baf3bfdbaa0dba, artefato11656591636. Preparações acima são históricas; entrega integrada.

### 9.80 Consulta local de diagnóstico — B06.5

Objetivo: perfil opcional diagnostics em ambos os aplicativos expondo health e metrics, preservando health padrão. Baseline Actuator/Micrometer já existentes, sem dependência ou instalação. Arquivos: dois application-diagnostics.yml, quatro testes HTTP e runbook infra/observability/README.md, registros e link público de execução. Risco: metrics inclui métricas automáticas JVM/HTTP; uso local restrito, sem alteração de Compose/Kubernetes/deploy ou autorização de exposição pública.

Aceite/TDD: caracterizar default health200/metrics404 com contador real registrado; diagnostics deve falhar por HTTP404 antes do perfil. Fixture primária nested @Configuration + @EnableAutoConfiguration, explicitamente escolhida, sem component scan nem infraestrutura DB/Rabbit. Contexto HTTP real em porta aleatória/loopback; registry real sem mocks. Após red, perfil mínimo e testes de COUNT, etiquetas fixas/filtro; env/beans/configprops404. Testes não substituem prova dos brokers de B06.3/B06.4. Foco local, suites afetadas e gates finais antes de integrar.

TDD local: default trans1/12,550s verde; diagnostics trans2/uma falha/11,959s pelo esperado200 observado404, zero erros/skips. Perfil mínimo então verify3/9,657s/Checkstyle0. Processador default verde e diagnostics com mesma falha404: total3/uma falha/13,137s, zero erros/skips; perfil mínimo então verify3/9,523s/Checkstyle0. Foco usa -Dtest=DefaultManagementHttpTest,DiagnosticsManagementHttpTest; suites locais §11: Trans172/22,020s e Proc82/20,103s, zero falhas/erros/skips/Checkstyle. Warnings esperados de propriedades inválidas em testes negativos permanecem; não alegar todos os logs sem warnings. Revisão técnica sem bloqueantes; runbook explica que perfil não impõe autenticação/loopback, métricas automáticas também acessíveis e contadores só aparecem após tentativa. CI real286/124 e gates finais ainda pendentes. Comandos de execução do runbook não substituem ensaio operacional com infraestrutura real.

Aceite final B06.5: PR #133 integrada em6ed1f36 após sete gates verdes de3d5e558. Trans211/run38017129231:286/2m52; Proc200/run38017129191:124/8m20, zero falhas/erros/skips/Hikari/dynamic em ambos. Seis novos testes HTTP na suíte normal. Inventory35/run38017129192:79/JAR222/imagem, mesmos três IDs; checkout4d9aa03c62436b93d3749bd1051563b0490c6046, artefato11656842214. Fluxo/Compose/imagens/scan verdes. Preparações acima históricas; perfil integrado, prova operacional de publishers reais próxima.

### 9.81 Diagnóstico da demo Compose — B06.6

Objetivo/aceite: switch Diagnostics explícito no roteiro local seleciona override de perfil nos dois apps, sem mudar Compose padrão. Mesmos cinco containers/três volumes, healthUP e lista metrics200; antes de Demo ler baselineconfirmed, depois dos dois resultados existentes aguardar delta>=2 em ambos no mesmo processo, sem reset. Retries contam; não prova correspondência exata com eventos nem outros outcomes. Smoke exige projeto/volumes novos e ambiente controlado. Down/up mantém registros/replay e comprova novamente endpoint, sem exigir contador zero. Override substitui lista de perfis por diagnostics; não combina automaticamente outros perfis. Host loopback preservado, peers Compose ainda alcançam endpoint interno, sem autenticação/deploy novo.

Arquivos previstos: demo.ps1, helper de COUNT e controles pequenos, compose.diagnostics.yaml, workflow Compose e runbooks/registros. Baseline PowerShell/Compose existentes, nenhuma dependência/instalação. TDD real primeiro: exigir lista metrics200 após healthUP no Smoke opt-in sem override, observar404 específico; startup/infra não são red. Após prova, override mínimo; controles rejeitam COUNT inválido/negativo/estrutura errada, distinguiem série lazy ausente. Manter preparo, políticas, flags, persistência, cleanup e timeout. Revisão de desenho sem bloqueantes. Engine local indisponível: integração real somente CI.

Red real do endpoint:7462358/run38017871281 falhou após healthUP nos dois apps e UID10001/mounts0/privilegedfalse, cleanup passou. A primeira mensagem não registrava status; repetição dac404f/run38017998523 comprovou HTTP200 esperado/404 observado após os mesmos guards, controle34s/cleanup2s. Sem falha de startup. Override acrescentado somente após essa prova. Controles locais de COUNT: stub0 falhou esperado2, leitura mínima verde; negativo-1 aceito produziu segundo red, validator mínimo verde. Caracterizações adicionais cobrem nome/statistic, arrays ausentes/objeto, tipos/NaN/infinito e labels fixas/subsets, com erro seguro; check-json preservado. Revisão sem bloqueantes. Parse0 e config Compose override quiet válidos. Nova asserção de delta>=2 após Demo usa leitor provisório0 para comprovar red de leitura antes da implementação HTTP; green operacional ainda pendente.

## 10. Observabilidade e SLOs de aprendizado



Primeiro contador de publicação do processador integrado em B06.3 (§9.78), com quatro outcomes fixos e sem nova exposição HTTP. Publicador de transações integrado em B06.4 (§9.79). As demais métricas candidatas são throughput, latência ponta a ponta, resultados, erros, retries, duplicatas e DLQ. Nome, unidade, labels e cardinalidade serão registrados quando instrumentados.

Não declarar SLO de produção fictício; usar objetivos de experimento local claramente rotulados.

## 11. Comandos reproduzíveis

Executar dentro de `transacoes-service/` no PowerShell:

```powershell
# versões
java -version
.\mvnw.cmd --version

# teste focado
.\mvnw.cmd -Dtest=TransacoesServiceApplicationTest test
.\mvnw.cmd -Dtest=TransacaoTest test
.\mvnw.cmd -Dtest=TransacaoRepositoryIntegrationTest test

# suíte e package
.\mvnw.cmd package

# verificação local sem infraestrutura, em transacoes-service
.\mvnw.cmd --batch-mode --no-transfer-progress -o '-Dtest=!**/*IntegrationTest,!**/*ApplicationTest,!**/*HttpTest,!PostgresRuntimeTest,!*E2E' '-DargLine=-XX:-EnableDynamicAgentLoading' verify

# ambiente local
.\mvnw.cmd spring-boot:run

# smoke test, com a aplicação ativa
Invoke-RestMethod http://localhost:8080/actuator/health
```

### Integração contínua

Em `processamento-service`, o verify offline sem infraestrutura usa `-Dtest=!**/*IntegrationTest,!**/*ApplicationTest,!**/*HttpTest` e `-DargLine=-XX:-EnableDynamicAgentLoading`; 75 testes/JAR verificados em9.59. Em transações, a seleção apenas por exclusões incluiu o E2E; o comando local acima exclui todas as classes terminadas em E2E, incluindo o novo smoke de imagens. CI padrão e Flow dedicado permanecem completos.

Cada serviço possui um workflow mínimo e independente: `.github/workflows/transacoes-service-ci.yml` e `.github/workflows/processamento-service-ci.yml`. Ambos executam a verificação Maven do respectivo módulo em ambiente Linux; filtros de caminho evitam rodar o outro build quando ele não foi afetado.

| Item | Decisão |
|---|---|
| Gatilhos | pull requests com mudanças no serviço ou no workflow; pushes relevantes para `main` |
| Runner | `ubuntu-latest`, com timeout de 10 minutos |
| Java | Temurin 21 por `actions/setup-java` |
| Maven | Wrapper com `--batch-mode --no-transfer-progress -DargLine=-XX:-EnableDynamicAgentLoading verify`; agente explícito no fork Surefire |
| Cache | dependências Maven, com chave derivada do `pom.xml` do respectivo serviço |
| Permissões | somente `contents: read` |
| Concorrência | execução anterior da mesma referência é cancelada quando fica obsoleta |
| Actions externas | referências fixadas por SHA, com a versão legível em comentário |

A validação local equivalente é `mvnw.cmd --batch-mode --no-transfer-progress -DargLine=-XX:-EnableDynamicAgentLoading verify` no Windows, com Docker Linux para os testes de infraestrutura. A [primeira execução do `transacoes-service`](https://github.com/Joaomagh/credpay/actions/runs/34542670040) concluiu o job `Maven verify` com sucesso em 32 segundos no runner Linux. O workflow do `processamento-service` replica deliberadamente as mesmas versões fixadas de Actions, permissões mínimas, cancelamento concorrente, timeout e comando, alterando apenas caminhos, diretório de trabalho, cache e nome. Sua [primeira execução remota](https://github.com/Joaomagh/credpay/actions/runs/35410768584) também ficou verde, com 1 teste e JAR gerado. Não há publicação, segredo, imagem ou deploy; CD permanece fora até existirem artefato e ambiente aprovados.

#### Evolução planejada do CI/CD

Cada capacidade será adicionada apenas quando existir o risco correspondente e uma forma objetiva de validá-la.

| Momento | Evolução planejada | Condição para entrar |
|---|---|---|
| Baseline atual | build, testes e geração do JAR com Maven `verify` | módulo Java e testes existentes |
| Persistência | PostgreSQL com Testcontainers e validação das migrations Flyway | primeiro adapter de persistência |
| Mensageria | RabbitMQ com Testcontainers e cenários de publicação, consumo e reentrega | primeiro contrato de evento |
| Qualidade | análise estática, estilo e relatórios de testes úteis | base de código suficiente para revelar problemas reais |
| Imagem | build, smoke test e análise de vulnerabilidades do container | Dockerfile aprovado e implementado |
| CD | publicação e implantação controladas, health check e estratégia de rollback | artefato, ambiente e política de entrega aprovados |

Cobertura, scanners e outras ferramentas serão sinais auxiliares, não metas isoladas. O pipeline não será ampliado apenas para aumentar a lista de tecnologias.

## 12. Decisões de arquitetura (ADR resumido)

### ADR-001 — Monorepo com serviços independentes

- **Status:** aceita
- **Contexto:** projeto solo, 5–10 h/semana e avaliação de portfólio.
- **Decisão:** um repositório, com build/imagem/configuração/dados separados por serviço.
- **Consequências:** operação e navegação simples; exige disciplina para não compartilhar domínio ou banco indevidamente.

### ADR-002 — Frontend fora da v1

- **Status:** aceita
- **Contexto:** o aprendizado prioritário é backend distribuído, Kubernetes e observabilidade.
- **Decisão:** demonstrar por OpenAPI, scripts e Grafana.
- **Consequências:** mais tempo para confiabilidade; experiência visual limitada, mitigada pelo roteiro de demo.

### ADR-003 — Contrato mínimo do sandbox AI-Jail

- **Status:** aceita; desenho ainda não implementado nem testado
- **Contexto:** o agente precisa trabalhar no CredPay sem receber acesso desnecessário ao host, a credenciais, ao daemon Docker ou à rede. O modelo de ameaça da seção 4.1 define os ativos e riscos; esta ADR transforma esses requisitos em um contrato de execução mínimo.
- **Decisão:** o sandbox será desenhado com negação por padrão e concessões explícitas:
  - o workspace do CredPay será o único bind mount do host;
  - o workspace será gravável apenas em incrementos que autorizem edição; inspeções usarão mount somente leitura quando a ferramenta de execução permitir;
  - o filesystem raiz do container será somente leitura;
  - dados transitórios usarão `/tmp` em `tmpfs`, sem execução, com tamanho limitado e descarte junto do container; outros diretórios graváveis só poderão ser adicionados após necessidade comprovada;
  - o processo executará como usuário não-root, sem `sudo` e sem binários `setuid` ou `setgid`;
  - todas as Linux capabilities serão removidas, `no-new-privileges` será habilitado e modo privilegiado será proibido;
  - Docker socket, outros sockets do host, dispositivos e diretórios da home do host não serão montados;
  - variáveis de ambiente serão construídas por allowlist; credenciais e ambiente do host não serão herdados;
  - a rede ficará desativada por padrão;
  - uma necessidade futura de egress exigirá aprovação, destinos e finalidade documentados e bloqueio aplicado por proxy ou firewall verificável; uma rede Docker `bridge` não satisfaz esse requisito;
  - CPU, memória, quantidade de processos e armazenamento temporário terão limites explícitos, dimensionados e registrados antes da implementação;
  - somente ferramentas previamente incluídas e aprovadas estarão disponíveis durante a execução; instalação ou download em runtime será proibido.
- **Política de ferramentas:** a imagem não incluirá Docker CLI nem ferramentas de administração do host. A lista mínima e as versões do runtime do agente ainda precisam ser verificadas antes da implementação. Java 21, Maven e ferramentas do projeto só entram quando um incremento demonstrar a necessidade e o Navigator aprovar sua inclusão.
- **Operação:** cada execução será efêmera. Persistência será limitada ao workspace explicitamente montado; processos e dados temporários deverão desaparecer quando o container for removido.
- **Evidência exigida:** estas decisões são requisitos de desenho, não garantias atuais. Cada controle somente será considerado verificado após teste negativo reproduzível registrar plataforma/versão, comando, resultado esperado, resultado observado e mecanismo que causou o bloqueio.
- **Consequências:** o desenho reduz o alcance de erro ou abuso, mas escrita autorizada ainda pode danificar o workspace. Filesystem somente leitura pode revelar a necessidade de novos `tmpfs`. Rede negada impede downloads e operações remotas. Ferramentas adicionais aumentam a superfície de ataque. Docker Desktop, daemon, VM, kernel/hypervisor e host permanecem na base confiável.

### ADR-004 — Outbox transacional antes da mensageria

- **Status:** aceita e implementada no `transacoes-service`
- **Contexto:** gravar a transação e publicar diretamente no RabbitMQ são duas operações independentes. Uma falha entre elas pode deixar um recurso confirmado sem evento ou publicar um evento de uma transação revertida.
- **Decisão:** persistir `TransacaoCriada` em uma outbox no mesmo PostgreSQL e na mesma transação local da criação. Um publicador separado enviará registros pendentes e os marcará depois da confirmação do broker.
- **Consequências:** elimina a janela entre commit do recurso e registro da intenção de publicação, mas não fornece entrega exatamente uma vez. Duplicatas após falha são esperadas; consumidores deverão deduplicar por `eventId`. A tabela cresce e exigirá política futura de retenção. Não há transação distribuída.

### ADR-005 — RabbitMQ com confirmação e propriedade de topologia

- **Status:** aceita; topologia/publicação dos dois serviços e listener opt-in testados em sucesso, replay, erros permanentes, retry e reentrega após perda de conexão; provisionamento operacional pendente.
- **Contexto:** a outbox remove a janela de gravação local, mas não prova que o broker recebeu ou roteou a mensagem. Declarar filas do consumidor no produtor também acoplaria implantações independentes.
- **Decisão:** usar exchange direct durável pertencente ao produtor, filas quorum pertencentes ao consumidor, mensagens persistentes, mandatory returns e publisher confirms correlacionados. Marcar a outbox somente após confirmação sem retorno.
- **Consequências:** falhas permanecem recuperáveis na outbox e recursos têm dono claro. A entrega continua pelo menos uma vez, exige deduplicação e adiciona latência/complexidade de confirmação. Alta disponibilidade não é comprovada pelo container de nó único.

### ADR-006 — Resultado idempotente e saída atômica antes do consumidor

- **Status:** resultado idempotente, saída atômica, ack após commit, rejeição permanente, retry limitado e reentrega após perda de conexão comprovados; ativação operacional e fluxo completo pendentes.
- **Contexto:** confirmar entrada sem preservar decisão e intenção de saída pode perder o resultado; reentregas podem ocorrer mesmo depois de um processamento bem-sucedido.
- **Decisão:** unicidade por evento recebido e transação, resultado/política congelados e outbox na mesma transação do banco próprio; listener só conclui após commit. Replay equivalente não recalcula; identidade divergente é conflito.
- **Consequências:** não há exatamente uma vez nem transação distribuída; persistência, concorrência, saída versionada e testes de falha são pré-requisitos do consumo operacional. O snapshot em memória é apenas a primeira etapa.

### ADR-007 — Serialização transacional das identidades de processamento

- **Status:** aceita e implementada no caso de uso; listener opt-in também comprovou replay, conflito e reentrega após perda de conexão. Concorrência foi comprovada diretamente no caso de uso com PostgreSQL real.
- **Contexto:** consultar ausência e inserir não faz duas primeiras entregas simultâneas convergirem; a constraint evita duplicata, mas pode expor erro SQL no replay. Compartilhar apenas `transactionId` não cobriria o mesmo `eventId` apresentado com outra transação.
- **Decisão:** derivar no PostgreSQL as chaves `bigint` de ambas as identidades, deduplicar e adquirir `pg_advisory_xact_lock` em ordem crescente das chaves efetivas, no início de uma transação `READ_COMMITTED`; depois aplicar a equivalência/conflito existentes e persistir. O banco mantém PK e unicidade como defesa final. Não criar tabela de locks nem retry cego neste incremento.
- **Consequências:** uma entrada concorrente espera o commit/rollback da outra e então lê a decisão durável; locks se liberam automaticamente. Uma colisão do hash de 64 bits reduz paralelismo, mas não muda a decisão. O teste com PostgreSQL real cobre concorrência, replay, conflitos e rollback; testes separados do listener cobrem replay/conflito/reentrega via mensageria. Isso não demonstra concorrência entre múltiplos consumidores nem throughput de produção.

## 13. Hurdles e aprendizados reais

> Sem quantidade obrigatória. Adicionar somente após reproduzir e entender o problema.

### H-001 — Codificação incorreta dos documentos iniciais

- **Sintoma:** acentos e símbolos apareciam como `Ã§` e `â€”` na leitura.
- **Causa provável:** bytes UTF-8 interpretados por uma codificação incompatível em alguma etapa.
- **Correção:** recriar os documentos em UTF-8 e verificar sua leitura no repositório.
- **Prevenção:** `.editorconfig`/configuração UTF-8 será avaliada na fundação.

## 14. Patterns realmente implementados

> Sem meta numérica. Registrar problema, local e trade-off; remover se o problema deixar de existir.

| Pattern | Local | Problema resolvido | Trade-off | Evidência |
|---|---|---|---|---|
| Porta de caso de uso | `application/CriarTransacao.java` | desacoplar o adaptador HTTP da execução da criação | uma abstração adicional para um único caso de uso | `TransacaoControllerTest` substitui a porta por mock; contexto completo usa `CriarTransacaoService` |
| Porta de consulta | `application/BuscarTransacao.java` | separar HTTP da leitura transacional e da persistência | outra abstração e mapeamento para um caso de uso simples | testes unitário, MVC e HTTP/PostgreSQL cobrem encontrado e ausente |

## 15. Registro de experimentos

| Data | Hipótese | Procedimento | Resultado | Aprendizado/próxima ação |
|---|---|---|---|---|
| 2026-07-11 | uma transação válida deve iniciar `PENDENTE` | executar red sem tipos de domínio; criar implementação mínima; repetir teste focado e suíte | red pelo motivo esperado; green focado e 2 testes verdes na suíte | testar o limite inferior do valor em novo ciclo TDD |
| 2026-07-11 | uma transação com valor zero deve ser rejeitada | adicionar teste de exceção; confirmar que nada era lançado; implementar somente condição igual a zero | red com 1 falha; green focado com 2 testes e suíte com 3 testes | testar valor negativo sem ampliar outras validações |
| 2026-09-10 | uma transação com valor negativo deve ser rejeitada | adicionar teste com `-0.01`; confirmar que nada era lançado; ampliar somente a condição de sinal | red com 1 falha; green focado com 3 testes e suíte com 4 testes | definir CI mínimo para executar as verificações em cada PR |
| 2026-09-10 | o build do `transacoes-service` deve ser verificado automaticamente | criar workflow com Java 21, Maven Wrapper, cache, permissões mínimas e filtro de caminhos; executar localmente e em PR | `verify` local gerou o JAR e executou 4 testes sem falhas; primeiro job Linux passou em 32 segundos | CI mínimo comprovado; evoluir somente quando novos riscos entrarem no sistema |
| 2026-09-10 | uma transação com valor nulo deve falhar com erro de domínio explícito | adicionar teste que espera `IllegalArgumentException`; confirmar o `NullPointerException` atual; adicionar guarda mínima e repetir verificações | red com 1 falha; green focado com 4 testes e `verify` com 5 testes | validar moeda ausente no próximo ciclo TDD |
| 2026-09-10 | uma transação sem moeda deve ser rejeitada | adicionar teste com valor válido e moeda nula; confirmar que nenhuma exceção era lançada; adicionar guarda mínima | red com 1 falha; green focado com 5 testes e `verify` com 6 testes | definir o contrato HTTP mínimo de criação antes de implementar o endpoint |
| 2026-09-11 | o happy path HTTP deve criar uma representação `PENDENTE` sem persistência | testar o adaptador MVC com caso de uso simulado; depois testar e implementar o caso de uso mínimo para manter a aplicação inicializável | dois reds de compilação pelo motivo esperado; testes focados verdes; `verify` com 8 testes e JAR gerado | implementar uma resposta `422 Problem Details` para valor zero |
| 2026-09-13 | o banco deve rejeitar valores não positivos ou não finitos mesmo sem passar pelo domínio | fazer INSERT SQL direto de `0`, negativo, `NaN` e infinitos antes/depois da V2 | red com 5 falhas esperadas; green focado com 7 casos e `verify` com 48 testes | provar colisão de UUID sem sobrescrita |
| 2026-09-13 | uma colisão de UUID deve falhar sem sobrescrever a transação original | inserir duas transações distintas com o mesmo ID em commits separados e reler a primeira | teste nasceu verde pela PK/persist; SQLState `23505`; teste focado com 8 casos e `verify` com 49 testes | provar busca ausente sem mascarar falha |
| 2026-09-13 | UUID inexistente deve retornar ausência sem engolir falhas | buscar ID fixo não inserido em PostgreSQL real e inspecionar a ausência de captura no adapter | teste nasceu verde; SELECT real; teste focado com 9 casos e `verify` com 50 testes | provar rollback da inserção |
| 2026-09-13 | rollback após INSERT deve deixar o banco sem o registro | persistir, forçar `flush`, marcar rollback e buscar em nova transação | teste nasceu verde; INSERT/SELECT reais; teste focado com 10 casos e `verify` com 51 testes | definir ativação obrigatória da persistência |
| 2026-09-13 | `POST /transacoes` deve persistir antes do `201` | red unitário da porta; red de contexto sem repository; ativar JPA/Flyway por padrão; executar POST e reler UUID em nova transação | 2 testes unitários, 25 HTTP e `verify` com 50 testes verdes | definir contrato HTTP de consulta por UUID |
| 2026-09-14 | `GET /transacoes/{id}` deve retornar o registro ou `404` para UUID válido ausente | red unitário dos tipos de aplicação; red MVC sem rota; consulta ponta a ponta após POST em PostgreSQL real | 2 testes de aplicação, 3 MVC, 27 HTTP e `verify` com 56 testes verdes | definir contrato de UUID malformado |
| 2026-09-14 | UUID malformado deve falhar antes da consulta sem expor detalhes internos | adicionar cenário MVC, observar handler genérico inadequado, implementar handler específico e validar aplicação completa | red com `422` e mensagem interna; green com 4 MVC, 28 HTTP e `verify` com 58 testes | definir contrato de idempotência da criação |

## 16. Higiene de publicação e dados locais

O repositório permanece público como portfólio educacional, sem autorização para publicar dados reais de clientes, credenciais ou material privado. Código, migrations SQL e documentos de engenharia são publicáveis quando não contiverem informação sensível; ignorar todos os arquivos Markdown não substitui revisão de conteúdo.

Em 2026-10-03, a revisão encontrou apenas `target/` nos dois `.gitignore` dos módulos e nenhum `.gitignore` na raiz. Foi adicionada uma política comum para `.env` e variantes, configuração local, diretórios `secrets/` e `.local/`, chaves/keystores, logs e dumps. `.env.example` permanece permitido somente com valores fictícios. `git check-ignore --no-index` validou 26 caminhos bloqueados e 13 permitidos, sem criar arquivos de teste, preservando README, spec, Maven Wrapper e migrations. A validação estrita de UTF-8 dos quatro arquivos e `git diff --check` passaram; o aviso LF/CRLF corresponde à normalização configurada no Git local.

A inspeção dos nomes rastreados e a busca por formatos comuns de chave privada, token GitHub e chave AWS não encontraram correspondências no snapshot atual. As referências a senha encontradas estão nas fixtures de Testcontainers. Os `application.yml` não possuem credenciais embutidas; os workflows usam `contents: read` e Actions fixadas por SHA. Esta verificação limitada não é auditoria completa de histórico, logs remotos ou todos os formatos de segredo.

**Limites:** `.gitignore` impede inclusão acidental de arquivos não rastreados; não remove arquivos já versionados, não apaga histórico e não impede `git add -f`. Se houver vazamento, revogar/rotacionar primeiro e tratar o histórico mediante autorização específica. Não publicar payload financeiro em logs. A verificação automatizada do histórico alcançável foi implementada em 16.1; revisão complementar de logs/artefatos permanece em B08.1 antes da entrega. Sandbox AI-Jail continua apenas documentado.

**Verificação adicional do histórico, 2026-10-03:** `git rev-list --all` enumerou 305 commits alcançáveis nas referências locais naquele momento. `git grep -l -I -E` em cada commit não encontrou os padrões pesquisados de chave privada, token GitHub/AWS/Slack ou chave OpenAI; o inventário de nomes históricos não encontrou `.env`, chaves/keystores, credenciais ou dumps/logs típicos. Somente contagens e nomes candidatos seriam exibidos, nunca valores. Isso não cobre commits remotos inalcançáveis, reflogs, logs/artefatos de CI nem todos os formatos de segredo. Não foi identificado vazamento que justifique rotação ou reescrita de histórico. A automação posterior está em 16.1; B08.1 continua parcialmente concluído, com revisão complementar pendente.

**Log verificado:** a busca limitada no log do job Maven do CI #133 não encontrou os formatos comuns de segredo acima nem candidatos às formas pesquisadas de payload bruto (`body`/`failedMessage` com `amount`, ou `amount` junto de `currency`). A mensagem observada para conflito foi fixa. A mesma busca nas mensagens dos commits locais também não encontrou os padrões. Essas buscas não cobrem outros formatos, outros logs nem artefatos; B08.1 não está concluído.

### 16.1 Baseline de detecção de segredos — B08.1

**Decisão:** adicionar Gitleaks CLI 8.30.1 como ferramenta de qualidade, sem dependência Java ou instalação global. A [release](https://github.com/gitleaks/gitleaks/releases/tag/v8.30.1) é publicada pelo projeto sob [MIT](https://github.com/gitleaks/gitleaks/blob/v8.30.1/LICENSE); [flags e modos](https://github.com/gitleaks/gitleaks/blob/v8.30.1/README.md) foram verificados. Artefatos conferidos no [manifesto oficial](https://github.com/gitleaks/gitleaks/releases/download/v8.30.1/gitleaks_8.30.1_checksums.txt):

| Plataforma | Arquivo | SHA256 |
|---|---|---|
| Linux x64, CI | gitleaks_8.30.1_linux_x64.tar.gz | 551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb |
| Windows x64, workspace | gitleaks_8.30.1_windows_x64.zip | d29144deff3a68aa93ced33dddf84b7fdc26070add4aa0f4513094c8332afc4e |

O download local fica limitado a `.local/gitleaks`, ignorado pelo Git, com validação SHA256 antes da extração. Nenhum hook/configuração anterior foi encontrado neste checkout; `git config --local core.hooksPath .githooks` habilita o hook versionado apenas neste repositório. `.gitattributes` mantém LF e o índice Git registra modo executável. O hook exige a versão aprovada, varre commits alcançáveis com `--all` antes do push e bloqueia se houver achado, falha do scanner ou binário ausente. A [semântica do hook pre-push](https://git-scm.com/docs/githooks#_pre_push) foi conferida.

Preparação em PowerShell, a partir da raiz deste repositório. Antes de configurar, verificar `git config --local --get core.hooksPath` e hooks existentes; não sobrescrever uma configuração anterior sem revisar sua integração:

```powershell
New-Item -ItemType Directory -Force .local/gitleaks | Out-Null
Invoke-WebRequest https://github.com/gitleaks/gitleaks/releases/download/v8.30.1/gitleaks_8.30.1_windows_x64.zip -OutFile .local/gitleaks/gitleaks.zip
$expected = 'd29144deff3a68aa93ced33dddf84b7fdc26070add4aa0f4513094c8332afc4e'
if ((Get-FileHash .local/gitleaks/gitleaks.zip -Algorithm SHA256).Hash.ToLowerInvariant() -ne $expected) { throw 'Checksum incorreto; não extrair nem executar' }
Expand-Archive .local/gitleaks/gitleaks.zip -DestinationPath .local/gitleaks -Force
.\.local\gitleaks\gitleaks.exe version
git config --local core.hooksPath .githooks
git hook run pre-push
if ($LASTEXITCODE -ne 0) { throw 'Hook não validado; não publicar' }
```

O workflow `.github/workflows/secret-scan.yml` usa checkout fixado por SHA, histórico completo, `persist-credentials: false`, somente `contents: read`, runner padrão e timeout de cinco minutos. Baixa o artefato Linux com checksum fixado, prova o detector com padrão sintético em diretório efêmero e varre histórico em PRs e pushes para main, sem filtro de caminhos. Sem verbose, relatórios publicados, artifacts, credencial fornecida ao scanner ou supressão automática; redaction 100% e comentários inline de allow ignorados. O checksum protege a escolha do artefato, mas o publicador e o runner/host continuam confiáveis.

**Limites:** CI detecta após o push; o hook local é a barreira antes da publicação, mas é contornável por `--no-verify`, desativação ou alteração local. Novos clones precisam preparar o binário e habilitar o hook; não é ativado automaticamente pelo clone. O scanner não prova ausência de todos os segredos/dados pessoais e não cobre logs externos nem commits órfãos. Nenhuma reescrita do histórico ou rotação foi feita sem vazamento identificado. Revisão complementar dos logs/artefatos segue pendente.

**Achados revisados:** a primeira varredura retornou código 1 e três fingerprints `generic-api-key`. A inspeção histórica e a revisão independente confirmaram uma routing key pública em asserção de topologia RabbitMQ e o mesmo UUID fictício de `Idempotency-Key` em dois commits do README. O header deduplica pedidos, não autentica. `.gitleaksignore` lista somente esses três fingerprints, com contexto; nenhuma regra, arquivo ou faixa de histórico é excluída. Um novo achado exige revisão própria, não inclusão automática nessa lista. Nenhum valor de credencial foi exibido e nenhum vazamento real foi confirmado.

**Controle positivo:** a primeira fixture sintética retornou 0 porque continha caracteres fora do padrão AWS reconhecido pela [regra oficial desta versão](https://github.com/gitleaks/gitleaks/blob/v8.30.1/config/gitleaks.toml). A fixture foi corrigida para o alfabeto aceito. A revisão identificou que o código padrão 1 também pode significar erro; o canário usa agora `--exit-code=42` e exige exatamente 42, distinguindo achado de falha operacional. É apenas um padrão fictício, construído em memória/diretório temporário, sem conta ou credencial real; prova detecção, não validade de acesso. Não foi reduzida a regra do scanner para fazer o teste passar.

**Validação:** checksum Windows conferido, CLI 8.30.1 executada; varredura `git --redact=100 --no-banner --log-level error --ignore-gitleaks-allow --log-opts="--all" .` retornou 0 após a revisão dos três fingerprints. Controle positivo via `stdin` retornou 42 com a flag exclusiva, inclusive com as exceções presentes; entrada limpa retornou 0. Hook ativado por `git config --local core.hooksPath .githooks`; `git hook run pre-push` retornou 0, índice confirmou modo `100755` e LF. `git check-ignore --no-index` confirmou binário/relatório em `.local` e os nove caminhos adicionais de credenciais bloqueados. O [Secret Scan #1](https://github.com/Joaomagh/credpay/actions/runs/37098101748) passou antes do ajuste de código exclusivo do canário; os runs #2 e #3 abaixo validaram o ajuste antes do merge. Push local passou pelo hook ativo. UTF-8 estrito e `git diff --check` passaram. Configuração operacional não recebe red artificial de negócio.

**Revisão final:** o [Secret Scan #2](https://github.com/Joaomagh/credpay/actions/runs/37098215430), SHA `95658c2`, passou com o canário exigindo 42 e histórico limpo. O [Secret Scan #3](https://github.com/Joaomagh/credpay/actions/runs/37098282309) também passou no SHA final `f819a88` antes da integração da [PR #90](https://github.com/Joaomagh/credpay/pull/90), commit `9ee6cf3`. A revisão assistida confirmou exceções estreitas, flags, permissões e limites; não é certificação externa. A inspeção limitada do log de #1 não encontrou o padrão sintético AWS completo sem redação. As buscas limitadas de logs registradas nos incrementos não equivalem à revisão completa de logs/artefatos ainda pendente em B08.1.

**Revisão de achados B04.12:** o hook bloqueou o primeiro push com três fingerprints `generic-api-key` no commit `7aeb70d`: artefato operacional (linha 14), teste (110) e runbook (50). A inspeção e outro agente confirmaram a mesma routing key pública da DLQ, que seleciona destino e não autentica. Foram adicionadas somente essas três exceções por fingerprint em `.gitleaksignore`, sem excluir arquivo/regra ou contornar o hook. Controle positivo via stdin continuou retornando exatamente `42`; entrada limpa retornou `0`. Nenhuma credencial real foi encontrada ou exibida. Varredura e hook serão repetidos antes do push.

**Revisão limitada de logs, 2026-10-04:** nos logs transações #174/#175/#176 e processador #158/#159, buscas em memória por formatos comuns de chave privada, tokens GitHub/OpenAI, chave AWS, formas pesquisadas de JSON financeiro e dumps AMQP retornaram zero candidatos. Nenhum conteúdo candidato foi impresso. A API de artifacts do processador #160 final retornou total_count0; workflows atuais não têm upload-artifact e usam contents:read. Esses recortes não cobrem todos os formatos, todos os logs históricos ou artefatos de outros runs; B08.1 continua com revisão complementar pendente. Nenhuma exclusão nova no scanner.

## 17. Checklist por incremento

- [ ] critério de aceitação entendido e escopo mantido;
- [ ] teste falhou pelo motivo esperado antes da implementação, quando aplicável;
- [ ] teste focado e suíte afetada verdes;
- [ ] logs sem segredos e warnings relevantes tratados;
- [ ] documentação/contratos/migrations atualizados;
- [ ] João consegue explicar a decisão e o trade-off;
- [ ] `task.md` aponta um único próximo passo.

B08.2g verificação estrutural: verify local75/zero falhas/erros/skips, Checkstyle0 em22,134s, compilação de todos os testes; não executa infraestrutura nem vale red. Log ignorado .local/evidence/b082g-red-local-verify.log. Revisão sem bloqueantes para publicar draft e provar red, sem implementar fechamento antecipado. git diff --check passou.

B08.2g green mínimo revisado sem bloqueantes: somente import/AFTER_CLASS após red. Verify local75/zero falhas/erros/skips e Checkstyle0 em22,135s, sem executar infraestrutura; foco real/servidor/pool/suíte114/gates pendentes. Log ignorado .local/evidence/b082g-green-local-verify.log.
