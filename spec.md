# CredPay — Especificação Técnica Viva

> Fonte de verdade do sistema que existe hoje. Preencher somente com decisão tomada, contrato aceito ou comportamento comprovado. Planos futuros ficam em `CREDPAY_PLAN.md`; próximas ações ficam em `task.md`.

**Última atualização:** 2026-09-18

**Fase atual:** 4 — Fluxo assíncrono confiável

**Estado:** criação e consulta HTTP persistentes; idempotência e outbox transacional comprovadas; publicação RabbitMQ confirmada com lote e scheduler opt-in de réplica única

## 1. Contexto e limites atuais

CredPay é um laboratório de processamento assíncrono de transações, sem dinheiro ou integrações financeiras reais. Terá dois serviços independentes em monorepo:

- `transacoes-service`: recebe pedidos, valida regras de entrada, mantém o estado consultável e publica eventos;
- `processamento-service`: consome pedidos de processamento, decide o resultado e publica o evento correspondente.

**Implementado:** `transacoes-service` com CI, regras de domínio, PostgreSQL/Flyway e endpoints de criação e consulta. A criação persiste a transação `PENDENTE`, exige chave idempotente, distingue primeira criação, replay equivalente e conflito, e grava atomicamente um `TransacaoCriada` v1 na outbox. O produtor declara uma exchange RabbitMQ durável e pode publicar manualmente uma pendência, marcando-a somente após `ack` sem retorno. O `processamento-service` possui scaffolding independente, build reproduzível, health check HTTP e decide `APROVADA` para valor menor ou igual ao limite e `REJEITADA` para valor acima dele.

**Ainda não implementado:** coordenação entre múltiplas réplicas publicadoras, consumidor, validação defensiva e configuração externa do limite, persistência do segundo serviço e constraints de moeda/status no banco. O adapter PostgreSQL é validado isoladamente e pelo fluxo HTTP completo; a constraint monetária foi testada por SQL direto.

## 2. Arquitetura vigente

### Contexto

```text
Cliente → transacoes-service ⇄ PostgreSQL
                    ↓ RabbitMQ ↑
          processamento-service ⇄ PostgreSQL
```

A mensageria é assíncrona, com consistência eventual e entrega pelo menos uma vez. O produtor usa idempotência na entrada HTTP, outbox transacional e confirms/returns no RabbitMQ. Consumo idempotente e retorno do resultado ainda serão implementados.

### Responsabilidades e propriedade dos dados

| Componente | Responsabilidade | Dados próprios |
|---|---|---|
| transacoes-service | entrada, visão consultável e publicação confiável | transações, chaves idempotentes e outbox |
| processamento-service | decisão de processamento | a definir |
| RabbitMQ | transporte, retry/DLQ conforme configuração futura | mensagens, não fonte de verdade |

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

O serviço usa as propriedades padrão do Spring para uma conexão PostgreSQL obrigatória. Os testes fornecem URL, usuário e senha fictícia dinamicamente; a execução real deve recebê-las do ambiente. Não existe perfil sem persistência nem credencial padrão versionada.

| Serviço | Variável | Obrigatória | Valor padrão | Propósito | Sensível |
|---|---|---|---|---|---|
| transacoes-service | `SPRING_DATASOURCE_URL` | sim | nenhum | URL JDBC do PostgreSQL do serviço | não |
| transacoes-service | `SPRING_DATASOURCE_USERNAME` | sim | nenhum | usuário do banco | sim |
| transacoes-service | `SPRING_DATASOURCE_PASSWORD` | sim | nenhum | senha do banco | sim |
| transacoes-service | `SPRING_RABBITMQ_HOST` / `SPRING_RABBITMQ_PORT` | para mensageria e health completo | padrões Spring Boot | endereço AMQP do broker | não |
| transacoes-service | `SPRING_RABBITMQ_USERNAME` / `SPRING_RABBITMQ_PASSWORD` | configurar conforme o broker | padrões Spring Boot; definir no ambiente | autenticação no RabbitMQ | sim |
| transacoes-service | `CREDPAY_OUTBOX_PUBLISHER_ENABLED` | não | `false` | ativa scheduler em uma única réplica | não |
| transacoes-service | `CREDPAY_OUTBOX_PUBLISHER_INTERVAL` | não | `PT1S` | intervalo após terminar um lote e atraso inicial | não |

Valores reais nunca entram neste documento. `.env.example` usa placeholders; arquivos locais de segredo devem ser ignorados pelo Git.

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
├── CLAUDE.md
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

`TransacaoCriada` v1 é persistido na outbox junto da criação e pode ser publicado com confirmação pelo RabbitMQ. O processamento pelo consumidor permanece planejado.

| Evento/versão | Produtor | Consumidor | Campos | Garantias |
|---|---|---|---|---|
| `TransacaoCriada` v1 | `transacoes-service` | `processamento-service` planejado | envelope versionado e dados da transação `PENDENTE` | intenção persistida atomicamente via outbox; publicação pelo menos uma vez com confirms/returns |

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

O evento representa um fato confirmado no banco, não um comando e não uma promessa de aprovação. A ordem global não é garantida. O consumidor futuro deve tolerar reentrega e deduplicar por `eventId`.

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
- **Limites:** valor zero e negativo, limite não positivo, moeda, configuração externa, eventos, RabbitMQ e persistência permanecem fora.

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

**Estado:** dependências, topologia do produtor, operações da outbox, publicação confirmada em lote e scheduler opt-in implementados. A primeira versão admite uma única réplica publicadora.

### 9.1 Topologia mínima

| Recurso | Nome | Tipo/propriedades | Responsável |
|---|---|---|---|
| exchange de eventos | `credpay.transacoes.v1` | direct, durável, não auto-delete | `transacoes-service` |
| routing key | `transacao.criada.v1` | literal versionado | contrato compartilhado |
| fila de processamento | `credpay.processamento.transacao-criada.v1` | quorum, durável, não exclusiva, não auto-delete | futuro `processamento-service` |
| dead-letter exchange | `credpay.processamento.dlx.v1` | direct, durável, não auto-delete | futuro `processamento-service` |
| dead-letter routing key | `transacao.criada.dlq.v1` | literal versionado | futuro `processamento-service` |
| dead-letter queue | `credpay.processamento.transacao-criada.dlq.v1` | quorum, durável, não exclusiva, não auto-delete | futuro `processamento-service` |

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

### 9.3 Consumo, retry e DLQ planejados

O futuro consumidor usará ack automático após sucesso do listener, prefetch inicial `10` e três tentativas totais para falhas transitórias, com esperas de 1 e 2 segundos. Depois disso, rejeitará sem requeue para a DLX. Payload inválido ou versão incompatível não será repetido indefinidamente: seguirá diretamente para DLQ com erro observável. A deduplicação persistente por `eventId` deverá existir antes de considerar o consumo confiável.

DLQ não é garantia absoluta de entrega: o dead-lettering também pode falhar. A v1 aceita essa limitação no ambiente local; quorum queues foram escolhidas porque suportam dead-lettering pelo menos uma vez quando configurado, mas cluster e falhas de quorum exigirão experimento próprio. Referência: [Dead Letter Exchanges](https://www.rabbitmq.com/docs/4.3/dlx).

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

## 10. Observabilidade e SLOs de aprendizado

Ainda não implementada. As métricas candidatas são throughput, latência ponta a ponta, resultados, erros, retries, duplicatas e DLQ. Nome, unidade, labels e cardinalidade serão registrados quando instrumentados.

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

# ambiente local
.\mvnw.cmd spring-boot:run

# smoke test, com a aplicação ativa
Invoke-RestMethod http://localhost:8080/actuator/health
```

### Integração contínua

Cada serviço possui um workflow mínimo e independente: `.github/workflows/transacoes-service-ci.yml` e `.github/workflows/processamento-service-ci.yml`. Ambos executam a verificação Maven do respectivo módulo em ambiente Linux; filtros de caminho evitam rodar o outro build quando ele não foi afetado.

| Item | Decisão |
|---|---|
| Gatilhos | pull requests com mudanças no serviço ou no workflow; pushes relevantes para `main` |
| Runner | `ubuntu-latest`, com timeout de 10 minutos |
| Java | Temurin 21 por `actions/setup-java` |
| Maven | Wrapper do repositório com `--batch-mode --no-transfer-progress verify` |
| Cache | dependências Maven, com chave derivada do `pom.xml` do respectivo serviço |
| Permissões | somente `contents: read` |
| Concorrência | execução anterior da mesma referência é cancelada quando fica obsoleta |
| Actions externas | referências fixadas por SHA, com a versão legível em comentário |

A validação local equivalente é `mvnw.cmd --batch-mode --no-transfer-progress verify` no Windows. A [primeira execução do `transacoes-service`](https://github.com/Joaomagh/credpay/actions/runs/34542670040) concluiu o job `Maven verify` com sucesso em 32 segundos no runner Linux. O workflow do `processamento-service` replica deliberadamente as mesmas versões fixadas de Actions, permissões mínimas, cancelamento concorrente, timeout e comando, alterando apenas caminhos, diretório de trabalho, cache e nome. Sua [primeira execução remota](https://github.com/Joaomagh/credpay/actions/runs/35410768584) também ficou verde, com 1 teste e JAR gerado. Não há publicação, segredo, imagem ou deploy; CD permanece fora até existirem artefato e ambiente aprovados.

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

- **Status:** aceita; lado produtor implementado, topologia e consumo do segundo serviço pendentes
- **Contexto:** a outbox remove a janela de gravação local, mas não prova que o broker recebeu ou roteou a mensagem. Declarar filas do consumidor no produtor também acoplaria implantações independentes.
- **Decisão:** usar exchange direct durável pertencente ao produtor, filas quorum pertencentes ao consumidor, mensagens persistentes, mandatory returns e publisher confirms correlacionados. Marcar a outbox somente após confirmação sem retorno.
- **Consequências:** falhas permanecem recuperáveis na outbox e recursos têm dono claro. A entrega continua pelo menos uma vez, exige deduplicação e adiciona latência/complexidade de confirmação. Alta disponibilidade não é comprovada pelo container de nó único.

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

## 16. Checklist por incremento

- [ ] critério de aceitação entendido e escopo mantido;
- [ ] teste falhou pelo motivo esperado antes da implementação, quando aplicável;
- [ ] teste focado e suíte afetada verdes;
- [ ] logs sem segredos e warnings relevantes tratados;
- [ ] documentação/contratos/migrations atualizados;
- [ ] João consegue explicar a decisão e o trade-off;
- [ ] `task.md` aponta um único próximo passo.
