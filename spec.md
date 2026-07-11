# CredPay — Especificação Técnica Viva

> Fonte de verdade do sistema que existe hoje. Preencher somente com decisão tomada, contrato aceito ou comportamento comprovado. Planos futuros ficam em `CREDPAY_PLAN.md`; próximas ações ficam em `task.md`.

**Última atualização:** 2026-07-11  
**Fase atual:** 1 — Governança e sandbox  
**Estado:** documentação-base em revisão; nenhum código de negócio iniciado

## 1. Contexto e limites atuais

CredPay é um laboratório de processamento assíncrono de transações, sem dinheiro ou integrações financeiras reais. Terá dois serviços independentes em monorepo:

- `transacoes-service`: recebe pedidos, valida regras de entrada, mantém o estado consultável e publica eventos;
- `processamento-service`: consome pedidos de processamento, decide o resultado e publica o evento correspondente.

**Ainda não implementado:** módulos, APIs, bancos, filas, contratos e infraestrutura. Eles serão registrados aqui quando nascerem de incrementos aprovados.

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
| Linguagem | Java | 21 (alvo) | ainda não fixada no build |
| Framework | Spring Boot | 3.x (alvo) | ainda não fixada |
| Banco | PostgreSQL | a definir | — |
| Mensageria | RabbitMQ | a definir | — |
| Testes | JUnit 5, Mockito, Testcontainers | a definir | — |

Substituir “alvo” por versão exata e comando de verificação quando o build existir.

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

## 4.3 Perfis e parâmetros propostos do sandbox

Esta seção separa o primeiro estágio mínimo de futuros runtimes de desenvolvimento. Os valores e ferramentas abaixo são propostas para revisão; ainda não foram implementados, medidos ou aprovados como configuração final.

### Hipótese pendente de topologia

A topologia em que o Codex permanece como controlador fora do container e envia comandos para um worker restrito dentro dele é apenas uma hipótese. Ela ainda não é decisão aprovada porque não foi definido um mecanismo técnico que impeça o controlador de executar comandos diretamente no host e contornar o sandbox.

O primeiro estágio pode validar os controles de um container isolado sem afirmar que toda execução do Codex passa por ele. Escolher a topologia final e seu mecanismo de enforcement exigirá aprovação explícita e testes próprios.

### Primeiro estágio: `sandbox-core`

O primeiro estágio proposto é um perfil mínimo para validar mounts, identidade, filesystem, capabilities, rede, recursos e ciclo de vida. Ele não inclui Java, Maven, clientes genéricos de rede nem o cliente Codex.

| Recurso | Valor proposto | Estado |
|---|---:|---|
| CPU | 1 CPU | pendente de validação |
| Memória | 1 GiB | pendente de validação |
| Swap | desabilitado | pendente de validação |
| Processos | 128 PIDs | pendente de validação |
| `/tmp` | 256 MiB | pendente de validação |

Os valores representam limites máximos propostos para o `sandbox-core`, não reservas ou requisitos de desempenho. O `/tmp` continuará previsto como `tmpfs`, descartável e com `noexec`. O bind mount do workspace não recebe cota simples de armazenamento por esses parâmetros e permanece como risco residual.

### Ferramentas mínimas propostas para o `sandbox-core`

| Grupo | Ferramentas | Finalidade |
|---|---|---|
| Shell | shell POSIX | executar comandos não interativos mínimos |
| Versionamento | Git | inspecionar o estado e o diff do workspace |
| Arquivos | `cp`, `mv`, `mkdir`, `find`, `stat` | manipular fixtures e inspecionar arquivos autorizados |
| Texto | `grep`, `sed`, `awk`, `sort`, `head`, `tail`, `wc`, `diff`, `xargs` | inspecionar conteúdo e produzir evidências simples |
| Identidade e ambiente | `id`, `env` | verificar usuário, grupo e allowlist de ambiente |
| Sistema | `mount`, `ps` | verificar mounts e processos visíveis |
| Integridade | `sha256sum` | identificar configuração e artefatos de teste |

O inventário efetivo da imagem deverá ser comparado com esta lista. Dependências transitivas trazidas pela imagem-base não serão consideradas automaticamente aprovadas. Gerenciadores de pacotes, Docker CLI, Docker Compose, `sudo`, `su`, SSH, clientes de nuvem, gerenciadores de credenciais, Java, Maven, outros runtimes e clientes genéricos como `curl` e `wget` ficam fora do `sandbox-core`.

O teste AJ-NET-01 precisará de uma forma controlada de realizar conexões. A escolha entre imagem diagnóstica derivada ou modo de execução separado permanece pendente; a ausência de cliente de rede no `sandbox-core` não será aceita como evidência de bloqueio.

### Perfil futuro: `sandbox-java`

O `sandbox-java` será um perfil futuro derivado do `sandbox-core`. Java 21, Maven e qualquer estratégia de cache de dependências somente poderão ser adicionados quando existir necessidade aprovada de compilar ou testar código Java. A inclusão exigirá novo inventário, análise de superfície, limites medidos e repetição dos testes negativos afetados.

### Decisões que exigem aprovação explícita

| Decisão | Motivo da aprovação |
|---|---|
| imagem-base, versão e digest | definem a superfície e a reprodutibilidade do runtime |
| valores finais de CPU, memória, swap, PIDs e `/tmp` | afetam disponibilidade e proteção do host |
| lista final de ferramentas e pacotes do `sandbox-core` | cada ferramenta amplia capacidade e superfície de ataque |
| identidade UID/GID e mapeamento de permissões | precisam funcionar com o workspace no Docker Desktop |
| topologia Codex/controlador e mecanismo de enforcement | determinam se comandos podem contornar o worker |
| método diagnóstico para AJ-NET-01 | adiciona capacidade de rede ao contexto de teste |
| criação do `sandbox-java` | adiciona JDK, Maven, cache e novos requisitos de recursos |
| estratégia de dependências sem egress | pode introduzir cache, mount ou origem adicional de artefatos |

## 5. Estrutura do repositório

No estado atual:

```text
credpay/
├── skills/
├── CLAUDE.md
├── CREDPAY_PLAN.md
├── spec.md
└── task.md
```

Atualizar somente após criar diretórios/arquivos.

## 6. Contratos

### API HTTP

Nenhum endpoint implementado.

| Método e rota | Request/response | Erros | Teste de contrato |
|---|---|---|---|
| — | — | — | — |

### Eventos

Nenhum evento implementado.

| Evento/versão | Produtor | Consumidor | Campos | Garantias |
|---|---|---|---|---|
| — | — | — | — | — |

Ao criar um evento, registrar versão, ID do evento, correlation ID, instante, chave de negócio e política de compatibilidade.

## 7. Modelo e regras implementadas

Nenhuma regra de negócio implementada. Cada entrada futura deve apontar para o teste que prova o comportamento.

| Regra | Casos/edge cases | Evidência automatizada |
|---|---|---|
| — | — | — |

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

Nenhum comando verificado ainda.

```bash
# build
# teste focado
# suíte completa
# lint
# subir/parar infraestrutura
# smoke test
```

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
| — | — | — | — | — |

## 15. Registro de experimentos

| Data | Hipótese | Procedimento | Resultado | Aprendizado/próxima ação |
|---|---|---|---|---|
| — | — | — | — | — |

## 16. Checklist por incremento

- [ ] critério de aceitação entendido e escopo mantido;
- [ ] teste falhou pelo motivo esperado antes da implementação, quando aplicável;
- [ ] teste focado e suíte afetada verdes;
- [ ] logs sem segredos e warnings relevantes tratados;
- [ ] documentação/contratos/migrations atualizados;
- [ ] João consegue explicar a decisão e o trade-off;
- [ ] `task.md` aponta um único próximo passo.
