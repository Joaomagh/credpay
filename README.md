# CredPay

> Laboratório de engenharia backend para estudar, construir e explicar um fluxo assíncrono de transações com decisões técnicas verificáveis.

O CredPay é um projeto educacional e de portfólio orientado a aprendizado prático em **Java**, **Spring Boot**, sistemas distribuídos e operação de software. A proposta não é simular um banco completo nem apenas reunir tecnologias: cada capacidade será introduzida por um incremento pequeno, acompanhada de testes, decisões registradas e limitações explícitas.

## Status atual

**Fase 1 — Governança e sandbox do agente.**

| Estado | Entrega |
|---|---|
| Concluído | acordo de trabalho, plano incremental e especificação técnica viva |
| Concluído | modelo de ameaça inicial do AI-Jail |
| Próximo | desenho do sandbox mínimo, ainda sem implementação |
| Planejado | aplicações Spring Boot, bancos, mensageria, observabilidade, Kubernetes e CI/CD |

Ainda não existem módulos de aplicação, endpoints, bancos, filas ou infraestrutura executável. As tecnologias e funcionalidades descritas adiante são **arquitetura e stack-alvo**, não entregas prontas. O estado factual do projeto é mantido em [`spec.md`](spec.md).

## Objetivo do projeto

O domínio escolhido é um fluxo pequeno de processamento assíncrono de transações financeiras fictícias. Ele cria um contexto concreto para estudar problemas relevantes de backend: consistência eventual, entrega de mensagens pelo menos uma vez, idempotência, publicação confiável, recuperação de falhas e observabilidade.

O resultado esperado é um sistema que possa ser demonstrado e, principalmente, explicado: quais riscos existem, por que cada decisão foi tomada, como os testes sustentam o comportamento e quais limites permanecem.

Este projeto não movimenta dinheiro e não integra PIX, cartões, instituições financeiras ou serviços de produção.

## O que este projeto busca demonstrar

- desenvolvimento backend com Java 21 e Spring Boot 3;
- desenho de serviços com responsabilidades e dados separados;
- comunicação orientada a eventos com RabbitMQ;
- consistência eventual e consumidores idempotentes;
- TDD em ciclos curtos de red, green e refactor;
- testes de contrato, unidade, integração e cenários de falha;
- persistência com PostgreSQL, JPA e migrations Flyway;
- observabilidade por logs estruturados, métricas e traces;
- execução reproduzível com containers e Kubernetes local;
- threat modeling e controles de execução para agentes de IA;
- documentação de decisões, trade-offs, experimentos e riscos residuais.

Esses itens serão considerados demonstrados somente quando houver implementação e evidência correspondente. Até lá, permanecem objetivos do roadmap.

## Arquitetura planejada

O monorepo terá dois aplicativos Spring Boot independentes. Cada serviço será responsável por seu próprio modelo, configuração, build, imagem e dados.

```text
Cliente
  │
  │ POST /transacoes
  ▼
transacoes-service ───── PostgreSQL
  │                           ▲
  │ TransacaoCriada           │ TransacaoProcessada
  ▼                           │
RabbitMQ ───────────► processamento-service ───── PostgreSQL
```

Fluxo planejado da v1:

1. `transacoes-service` valida a entrada e persiste uma transação `PENDENTE`;
2. um evento `TransacaoCriada` é publicado de forma confiável;
3. `processamento-service` consome o evento e decide entre `APROVADA` e `REJEITADA`;
4. o resultado retorna por um evento `TransacaoProcessada`;
5. `transacoes-service` atualiza o estado consultável;
6. logs, métricas e traces permitem acompanhar o fluxo e diagnosticar falhas.

A entrega da mensageria será tratada como **pelo menos uma vez**. Por isso, duplicação, idempotência, retry limitado, DLQ e publicação confiável serão requisitos testados, não pressupostos.

### Stack-alvo

| Área | Tecnologias planejadas | Finalidade |
|---|---|---|
| Aplicação | Java 21, Spring Boot 3, Maven | serviços backend independentes |
| Dados | PostgreSQL, Spring Data JPA, Flyway | persistência e migrations reproduzíveis |
| Mensageria | RabbitMQ, Spring AMQP | fluxo assíncrono e tratamento de reentrega |
| Testes | JUnit 5, AssertJ, Mockito, Testcontainers | comportamento e infraestrutura real nas bordas |
| Contrato | Bean Validation, OpenAPI | API pequena e explícita |
| Observabilidade | Actuator, Micrometer, Prometheus, Grafana | diagnóstico baseado em evidência |
| Plataforma | Docker Compose, Kubernetes local | execução e operação reproduzíveis |
| Entrega | GitHub Actions e verificações de qualidade | feedback automatizado e rastreabilidade |

Versões exatas só serão declaradas após serem fixadas e verificadas no build.

## Práticas de engenharia

### Desenvolvimento incremental e TDD

O trabalho é dividido em incrementos verticais pequenos. Para comportamento de negócio, o ciclo obrigatório é:

1. descrever um comportamento observável;
2. criar o menor teste e confirmar a falha pelo motivo esperado;
3. implementar apenas o necessário para o teste passar;
4. executar o teste focado e a suíte afetada;
5. refatorar sem alterar comportamento;
6. registrar somente o que foi comprovado.

Mocks são reservados a fronteiras externas quando ajudam a isolar o comportamento. PostgreSQL e RabbitMQ serão exercitados com infraestrutura real nos testes de integração quando essas fronteiras forem implementadas.

### Decisões e aprendizado verificável

Decisões arquiteturais, contratos e limitações não ficam apenas no código. O repositório mantém uma especificação viva, um plano de longo prazo e uma única próxima tarefa. Problemas e patterns são registrados apenas quando surgem de uma necessidade real — não para preencher uma lista de palavras-chave.

Essa documentação de processo é pública de propósito. Ela permite avaliar não só o resultado final, mas também a evolução do raciocínio: definição de escopo, análise de risco, critérios de aceitação, evidências e correções de percurso. A assistência de IA faz parte do processo, com decisões de direção e aprovação mantidas pelo responsável pelo projeto.

## Segurança e AI-Jail

A primeira fase começa pela segurança do próprio ambiente de desenvolvimento assistido. O modelo de ameaça do AI-Jail identifica arquivos externos ao workspace, credenciais, Docker socket, host e redes não autorizadas como ativos a proteger contra comportamento incorreto, prompt injection ou ferramentas maliciosas.

O sandbox planejado parte de alguns princípios:

- usuário não-root e capabilities mínimas;
- somente o workspace necessário montado;
- nenhum Docker socket, segredo ou home do host exposto;
- rede negada por padrão, com exceções aplicadas por controle verificável;
- limites de recursos e armazenamento descartável;
- testes negativos para filesystem, credenciais, privilégio, rede e persistência.

O threat model também registra limites importantes: Docker Desktop, daemon, VM, kernel/hypervisor e host permanecem parte da base confiável. Um container restringido reduz alcance, mas não equivale a uma máquina física isolada. Nenhuma garantia de sandbox será apresentada como concluída antes da implementação e dos testes negativos. Os detalhes estão em [`spec.md`](spec.md#41-modelo-de-ameaça-do-ai-jail).

## Roadmap

| Fase | Objetivo | Estado |
|---|---|---|
| 1 | governança, threat model e sandbox verificável | em andamento |
| 2 | fundação dos dois serviços e ambiente local reproduzível | planejada |
| 3 | primeiro incremento TDD e integração com PostgreSQL | planejada |
| 4 | fluxo assíncrono, idempotência, outbox, retry e DLQ | planejada |
| 5 | experimentos de falha e resiliência orientada por evidência | planejada |
| 6 | Kubernetes local e observabilidade | planejada |
| 7 | CI/CD, documentação de execução e roteiro de demonstração | planejada |

O plano detalhado, os critérios de saída e o que está fora da v1 estão em [`CREDPAY_PLAN.md`](CREDPAY_PLAN.md).

## Como acompanhar a evolução

Cada incremento deve produzir uma evidência pequena e verificável. Para acompanhar o projeto sem confundir intenção com implementação:

1. consulte [`task.md`](task.md) para o próximo passo único;
2. consulte [`spec.md`](spec.md) para fatos, decisões e comportamentos já aceitos ou comprovados;
3. consulte [`CREDPAY_PLAN.md`](CREDPAY_PLAN.md) para direção e fases futuras;
4. acompanhe o histórico de commits para ver cada mudança no contexto em que foi introduzida.

Comandos de build, testes e execução local serão publicados aqui quando existirem e forem verificados.

## Documentação do projeto

| Documento | Papel |
|---|---|
| [`CLAUDE.md`](CLAUDE.md) | acordo de trabalho, autonomia, segurança e protocolo dos incrementos |
| [`CREDPAY_PLAN.md`](CREDPAY_PLAN.md) | visão, arquitetura-alvo, fases e controle de escopo |
| [`spec.md`](spec.md) | fonte de verdade técnica, ADRs, contratos e evidências atuais |
| [`task.md`](task.md) | próximo passo único aprovado |
| [`skills/`](skills/) | guias repetíveis para TDD, endpoints e testes de integração |

## Escopo e uso

CredPay é um estudo educacional e não deve ser usado para processar dados ou transações financeiras reais. Autenticação de usuários, frontend, PIX, cartões, antifraude real, transação distribuída, multi-região e alta disponibilidade de produção estão fora do escopo da v1.

O repositório ainda não declara uma licença de software. Até que uma licença seja adicionada explicitamente, o conteúdo está disponível para leitura e avaliação, sem concessão automática de direitos de reutilização ou distribuição.
