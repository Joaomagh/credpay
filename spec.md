# CredPay — Especificação Técnica Viva

> Fonte de verdade do sistema que existe hoje. Preencher somente com decisão tomada, contrato aceito ou comportamento comprovado. Planos futuros ficam em `CREDPAY_PLAN.md`; próximas ações ficam em `task.md`.

**Última atualização:** 2026-09-12

**Fase atual:** 2 — Fundação reproduzível

**Estado:** scaffolding, CI, cinco regras de domínio e criação HTTP com respostas `201`, `400` e `422` testadas; sem persistência

## 1. Contexto e limites atuais

CredPay é um laboratório de processamento assíncrono de transações, sem dinheiro ou integrações financeiras reais. Terá dois serviços independentes em monorepo:

- `transacoes-service`: recebe pedidos, valida regras de entrada, mantém o estado consultável e publica eventos;
- `processamento-service`: consome pedidos de processamento, decide o resultado e publica o evento correspondente.

**Implementado:** scaffolding mínimo do `transacoes-service`, CI com Maven `verify`, cinco regras de domínio e o happy path de `POST /transacoes`, que retorna uma representação não persistida com UUID e estado `PENDENTE`.

**Ainda não implementado:** persistência e consulta de transações, política estrita de tipos JSON, `processamento-service`, filas, contratos de eventos e infraestrutura. A API já retorna `422 Problem Details` para valor ausente/nulo/não positivo e moeda ausente/nula/inválida, além de `400 Problem Details` para falhas de leitura do corpo.

## 2. Arquitetura vigente

### Contexto

```text
Cliente → transacoes-service ⇄ PostgreSQL
                    ↓ RabbitMQ ↑
          processamento-service ⇄ PostgreSQL
```

A mensageria é assíncrona, com consistência eventual e expectativa de entrega pelo menos uma vez. O mecanismo exato de publicação confiável, idempotência e retorno de resultado será decidido/testado no incremento correspondente.

### Responsabilidades e propriedade dos dados

| Componente | Responsabilidade | Dados próprios |
|---|---|---|
| transacoes-service | entrada e visão consultável da transação | a definir |
| processamento-service | decisão de processamento | a definir |
| RabbitMQ | transporte, retry/DLQ conforme configuração futura | mensagens, não fonte de verdade |

## 3. Stack e versões verificadas

| Área | Tecnologia | Versão fixada | Evidência |
|---|---|---:|---|
| Linguagem | Java | 21 | `java -version`: 21.0.6 |
| Framework | Spring Boot | 3.5.16 | parent fixado no `transacoes-service/pom.xml`; teste verde |
| Build | Maven Wrapper | 3.9.16 | `transacoes-service/mvnw.cmd --version` |
| Banco | PostgreSQL | a definir | — |
| Mensageria | RabbitMQ | a definir | — |
| Testes | JUnit 5, Mockito, Testcontainers | a definir | — |

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

## 4. Configuração e segredos

Nenhuma variável existe ainda. Registrar cada uma quando for consumida pelo código/configuração.

| Serviço | Variável | Obrigatória | Valor padrão | Propósito | Sensível |
|---|---|---|---|---|---|
| — | — | — | — | — | — |

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

O contrato abaixo foi aprovado em 2026-09-10. Até 2026-09-12, há testes HTTP do happy path, falhas de leitura e validações de valor/moeda. Isso não implica cobertura exaustiva: coerções escalares do Jackson ainda exigem política e testes próprios. A criação síncrona confirma que uma representação nasceu `PENDENTE`, mas ela ainda não é persistida; o processamento assíncrono permanece planejado.

| Método e rota | Request/response | Erros | Teste de contrato |
|---|---|---|---|
| `POST /transacoes` | JSON com `valor` e `moeda`; `201 Created`, `Location` e representação `PENDENTE` | `400` para corpo ilegível; `422` para valor ausente/nulo/não positivo e moeda ausente/nula/inválida | `TransacaoControllerTest` e `TransacaoHttpTest` |

#### Criação de transação

Request com `Content-Type: application/json`:

```json
{
  "valor": 10.00,
  "moeda": "BRL"
}
```

- `valor`: número decimal obrigatório e maior que zero;
- `moeda`: código alfabético ISO 4217 obrigatório, em letras maiúsculas;
- o cliente não informa ID nem status.

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
| `valor` ausente ou nulo | `422 Unprocessable Entity` | `Transação inválida` | `valor deve ser informado` |
| `valor` igual ou menor que zero | `422 Unprocessable Entity` | `Transação inválida` | `valor deve ser maior que zero` |
| `moeda` ausente ou nula | `422 Unprocessable Entity` | `Transação inválida` | `moeda deve ser informada` |
| código de `moeda` inválido | `422 Unprocessable Entity` | `Transação inválida` | `moeda deve ser um código ISO 4217 válido em letras maiúsculas` |

O contrato não inclui persistência, consulta, idempotência, autenticação, OpenAPI ou publicação de evento neste estágio. O UUID retornado não identifica um registro durável e não pode ser consultado. Cada capacidade terá teste e decisão próprios.

### Eventos

Nenhum evento implementado.

| Evento/versão | Produtor | Consumidor | Campos | Garantias |
|---|---|---|---|---|
| — | — | — | — | — |

Ao criar um evento, registrar versão, ID do evento, correlation ID, instante, chave de negócio e política de compatibilidade.

## 7. Modelo e regras implementadas

| Regra | Casos/edge cases | Evidência automatizada |
|---|---|---|
| Uma transação válida nasce `PENDENTE` | fixture válida usa `10.00` e `BRL`; valores nulo, zero e negativo, além de moeda nula, são rejeitados | `TransacaoTest.criar_deveDefinirStatusPendente_quandoTransacaoForValida` |
| Uma transação não pode ser criada com valor zero | lança `IllegalArgumentException` com mensagem `valor deve ser maior que zero` | `TransacaoTest.criar_deveRejeitar_quandoValorForZero` |
| Uma transação não pode ser criada com valor negativo | o menor caso testado usa `-0.01`; lança `IllegalArgumentException` com a mesma mensagem da fronteira zero | `TransacaoTest.criar_deveRejeitar_quandoValorForNegativo` |
| Uma transação não pode ser criada com valor nulo | lança `IllegalArgumentException` com mensagem `valor deve ser informado`, antes de avaliar o sinal | `TransacaoTest.criar_deveRejeitar_quandoValorForNulo` |
| Uma transação não pode ser criada com moeda nula | lança `IllegalArgumentException` com mensagem `moeda deve ser informada` | `TransacaoTest.criar_deveRejeitar_quandoMoedaForNula` |
| O happy path HTTP cria uma representação pendente | request `10.00`/`BRL`; retorna `201`, `Location`, UUID, valor, moeda e `PENDENTE` | `TransacaoControllerTest.deveCriarTransacaoPendente` |

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
- **Limite:** a transação ainda não armazena valor ou moeda e não possui ID ou timestamp. Não há persistência nem API.

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
- **Objetivo:** cobrir criação válida e valor negativo/ausente/nulo com controller, caso de uso e domínio reais. O teste MVC isolado continua cobrindo a fronteira separadamente.
- **Resultado:** os novos casos passaram na primeira execução; são testes de regressão de comportamento existente, não um novo ciclo red/green. Nenhum código de produção foi alterado.
- **Sucesso:** `201`, valor/moeda/estado esperados, UUID canônico e `Location` contendo exatamente o ID da resposta.
- **Erros:** `422` com Problem Details completo e sem `Location`; mensagens `valor deve ser informado` e `valor deve ser maior que zero` conforme o caso.
- **Verificação:** teste HTTP focado com 16 casos verdes; Maven Wrapper `verify` com 24 testes verdes e JAR gerado. `pom.xml` e `src/main` inalterados.
- **Revisão periódica:** falta tornar explícita a rejeição de valor monetário textual no JSON, sem coerção silenciosa. Persistência e consulta continuam ausentes; warnings Mockito/Byte Buddy permanecem registrados.

## 8. Persistência e consistência

Nenhuma migration criada. Registrar ownership, tabelas, constraints, índices e estratégia de concorrência quando existirem.

| Serviço | Migration | Mudança | Motivo |
|---|---|---|---|
| — | — | — | — |

## 9. Mensageria e tratamento de falhas

Ainda não configurado. Decisões futuras devem cobrir exchange, queue, routing key, durabilidade, ack, prefetch, retry/backoff, DLQ, idempotência e publicação confiável — somente quando testadas.

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

# suíte e package
.\mvnw.cmd package

# ambiente local
.\mvnw.cmd spring-boot:run

# smoke test, com a aplicação ativa
Invoke-RestMethod http://localhost:8080/actuator/health
```

### Integração contínua

O `transacoes-service` possui um workflow mínimo em `.github/workflows/transacoes-service-ci.yml` para executar a mesma verificação Maven em ambiente Linux.

| Item | Decisão |
|---|---|
| Gatilhos | pull requests com mudanças no serviço ou no workflow; pushes relevantes para `main` |
| Runner | `ubuntu-latest`, com timeout de 10 minutos |
| Java | Temurin 21 por `actions/setup-java` |
| Maven | Wrapper do repositório com `--batch-mode --no-transfer-progress verify` |
| Cache | dependências Maven, com chave derivada de `transacoes-service/pom.xml` |
| Permissões | somente `contents: read` |
| Concorrência | execução anterior da mesma referência é cancelada quando fica obsoleta |
| Actions externas | referências fixadas por SHA, com a versão legível em comentário |

A validação local equivalente é `mvnw.cmd --batch-mode --no-transfer-progress verify` no Windows. A [primeira execução no GitHub Actions](https://github.com/Joaomagh/credpay/actions/runs/34542670040) concluiu o job `Maven verify` com sucesso em 32 segundos no runner Linux. Não há publicação, segredo, imagem ou deploy; CD permanece fora até existirem artefato e ambiente aprovados.

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

## 16. Checklist por incremento

- [ ] critério de aceitação entendido e escopo mantido;
- [ ] teste falhou pelo motivo esperado antes da implementação, quando aplicável;
- [ ] teste focado e suíte afetada verdes;
- [ ] logs sem segredos e warnings relevantes tratados;
- [ ] documentação/contratos/migrations atualizados;
- [ ] João consegue explicar a decisão e o trade-off;
- [ ] `task.md` aponta um único próximo passo.
