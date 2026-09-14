# CredPay — Especificação Técnica Viva

> Fonte de verdade do sistema que existe hoje. Preencher somente com decisão tomada, contrato aceito ou comportamento comprovado. Planos futuros ficam em `CREDPAY_PLAN.md`; próximas ações ficam em `task.md`.

**Última atualização:** 2026-09-13

**Fase atual:** 2 — Fundação reproduzível

**Estado:** criação HTTP persistente e transacional; repository PostgreSQL/JPA com round-trip, rollback, colisão, ausência e constraint monetária comprovados

## 1. Contexto e limites atuais

CredPay é um laboratório de processamento assíncrono de transações, sem dinheiro ou integrações financeiras reais. Terá dois serviços independentes em monorepo:

- `transacoes-service`: recebe pedidos, valida regras de entrada, mantém o estado consultável e publica eventos;
- `processamento-service`: consome pedidos de processamento, decide o resultado e publica o evento correspondente.

**Implementado:** scaffolding mínimo do `transacoes-service`, CI com Maven `verify`, validações de domínio, preservação de UUID/valor/moeda e `POST /transacoes`, que persiste e retorna a mesma transação `PENDENTE` depois do commit local.

**Ainda não implementado:** consulta HTTP de transações, constraints de moeda/status no banco, `processamento-service`, filas e contratos de eventos. O adapter PostgreSQL é validado isoladamente e pelo fluxo HTTP completo; a constraint monetária foi testada por SQL direto. A API já retorna `422 Problem Details` para valor ausente/nulo/não positivo e moeda ausente/nula/inválida, além de `400 Problem Details` para falhas de leitura, valor textual e moeda numérica/booleana.

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
| Banco | PostgreSQL | 17.11 | testes de runtime, repository e fluxo HTTP persistente com imagem fixada por digest |
| Mensageria | RabbitMQ | a definir | — |
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

## 4. Configuração e segredos

O serviço usa as propriedades padrão do Spring para uma conexão PostgreSQL obrigatória. Os testes fornecem URL, usuário e senha fictícia dinamicamente; a execução real deve recebê-las do ambiente. Não existe perfil sem persistência nem credencial padrão versionada.

| Serviço | Variável | Obrigatória | Valor padrão | Propósito | Sensível |
|---|---|---|---|---|---|
| transacoes-service | `SPRING_DATASOURCE_URL` | sim | nenhum | URL JDBC do PostgreSQL do serviço | não |
| transacoes-service | `SPRING_DATASOURCE_USERNAME` | sim | nenhum | usuário do banco | sim |
| transacoes-service | `SPRING_DATASOURCE_PASSWORD` | sim | nenhum | senha do banco | sim |

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
| `POST /transacoes` | JSON com `valor` e `moeda`; `201 Created`, `Location` e representação `PENDENTE` | `400` para corpo ilegível; `422` para valor ausente/nulo/não positivo e moeda ausente/nula/inválida | `TransacaoControllerTest` e `TransacaoHttpTest` |
| `GET /transacoes/{id}` | `200 OK` e representação persistida | `400` para UUID malformado; `404` para UUID válido ausente | encontrado e ausente cobertos por `BuscarTransacaoServiceTest`, `TransacaoControllerTest` e `TransacaoHttpTest`; malformado planejado |

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
| `valor` enviado como texto, inclusive vazio ou espaços | `400 Bad Request` | `Requisição inválida` | `corpo deve conter um JSON válido com valor numérico e moeda textual` |
| `moeda` enviada como número ou booleano | `400 Bad Request` | `Requisição inválida` | `corpo deve conter um JSON válido com valor numérico e moeda textual` |
| `valor` ausente ou nulo | `422 Unprocessable Entity` | `Transação inválida` | `valor deve ser informado` |
| `valor` igual ou menor que zero | `422 Unprocessable Entity` | `Transação inválida` | `valor deve ser maior que zero` |
| `moeda` ausente ou nula | `422 Unprocessable Entity` | `Transação inválida` | `moeda deve ser informada` |
| código de `moeda` inválido | `422 Unprocessable Entity` | `Transação inválida` | `moeda deve ser um código ISO 4217 válido em letras maiúsculas` |

A criação e a consulta por UUID válido já são persistentes. Idempotência, autenticação, OpenAPI e publicação de evento permanecem fora deste estágio.

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

**Contrato aprovado, ainda não implementado:** quando `{id}` não puder ser convertido para UUID, a API retornará:

- status `400 Bad Request`;
- `Content-Type: application/problem+json`;
- `type: about:blank`;
- `title: Requisição inválida`;
- `detail: id deve ser um UUID válido`;
- `instance` igual à rota recebida.

Esse erro representa sintaxe inválida e ocorre antes do caso de uso. O teste MVC deverá verificar que `BuscarTransacao` não é chamado; o teste HTTP completo deverá comprovar o mesmo contrato externo sem exigir estado prévio no banco. Mensagens internas do conversor, nome de classe Java e stack trace não serão expostos. UUID vazio, parâmetros extras, normalização textual e outros identificadores não entram neste incremento.

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
| Uma transação preserva seus dados monetários validados | mantém valor, escala decimal e moeda em campos finais, sem arredondamento; fixtures `10.00/BRL` e `123.456/USD` | `TransacaoTest.criar_devePreservarValorEMoeda_quandoTransacaoForValida` |
| Uma transação preserva sua identidade | recebe UUID explícito na fábrica e o conserva em campo privado final, sem setter; duas fixtures de ID | `TransacaoTest.criar_devePreservarId_quandoTransacaoForValida` |
| Uma transação não pode ser criada sem identidade | ID nulo lança `IllegalArgumentException` com mensagem `id deve ser informado` | `TransacaoTest.criar_deveRejeitar_quandoIdForNulo` |
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
- **Decisão:** distinguir sintaxe inválida (`400`) de ausência de recurso (`404`) e manter uma mensagem pública estável, sem detalhes do conversor Java.
- **Fronteira:** a conversão do path ocorre antes da porta `BuscarTransacao`; o teste MVC deverá provar ausência de interação com o caso de uso.
- **Evidência esperada:** slice MVC e HTTP completo validam status, media type e todos os campos Problem Details. O `404` de UUID válido permanece como regressão.
- **Limites:** sem código neste incremento documental; não cobre rota sem ID, UUID válido ausente, listagem ou outro tipo de parâmetro.
- **Próximo:** implementar o contrato em TDD sem dependência nova.

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
.\mvnw.cmd -Dtest=TransacaoRepositoryIntegrationTest test

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

## 16. Checklist por incremento

- [ ] critério de aceitação entendido e escopo mantido;
- [ ] teste falhou pelo motivo esperado antes da implementação, quando aplicável;
- [ ] teste focado e suíte afetada verdes;
- [ ] logs sem segredos e warnings relevantes tratados;
- [ ] documentação/contratos/migrations atualizados;
- [ ] João consegue explicar a decisão e o trade-off;
- [ ] `task.md` aponta um único próximo passo.
