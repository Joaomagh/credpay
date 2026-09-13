# CredPay

[![Transaction Service CI](https://github.com/Joaomagh/credpay/actions/workflows/transacoes-service-ci.yml/badge.svg)](https://github.com/Joaomagh/credpay/actions/workflows/transacoes-service-ci.yml)

> Laboratório de engenharia backend para construir e explicar, com evidências, um fluxo assíncrono de transações.

O CredPay é um projeto educacional e de portfólio em evolução. Seu foco não é imitar um banco nem acumular tecnologias, mas exercitar decisões que aparecem em sistemas backend reais: regras de domínio, consistência eventual, idempotência, publicação confiável, recuperação de falhas, observabilidade e entrega reproduzível.

Cada capacidade entra em um incremento pequeno, testado e documentado. Assim, o repositório mostra não apenas o resultado, mas também o raciocínio de engenharia que levou até ele.

## Status atual

O projeto está na fundação do primeiro serviço e nos primeiros ciclos de TDD do domínio e da API.

| Estado | Entrega |
|---|---|
| Implementado | `transacoes-service` com Java 21, Spring Boot 3.5.16 e Maven Wrapper 3.9.16 |
| Implementado | health check do Spring Boot Actuator |
| Implementado | transação válida nasce `PENDENTE` |
| Implementado | valor ausente ou não positivo e moeda ausente são rejeitados pelo domínio |
| Implementado | transação preserva valor, escala decimal e moeda validados, sem arredondamento |
| Implementado | `POST /transacoes` cria uma representação não persistida com UUID e estado `PENDENTE` |
| Implementado | `422 Problem Details` para valor ausente/nulo/não positivo e moeda ausente/nula/inválida, com contexto real |
| Implementado | `400 Problem Details` seguro para corpo ausente/nulo, JSON malformado ou estrutura incompatível |
| Implementado | valor textual e moeda numérica/booleana no JSON são rejeitados com `400`, sem conversão silenciosa |
| Implementado | identidade UUID imutável no domínio, reutilizada na resposta; ID nulo rejeitado |
| Implementado | repository JPA com migration Flyway e escrita/leitura PostgreSQL após commit, ainda desacoplado do endpoint |
| Implementado | 43 testes automatizados verdes, incluindo HTTP sem mocks e integração PostgreSQL/Testcontainers |
| Implementado | CI no GitHub Actions com Maven `verify` em Java 21/Linux |
| Documentado | threat model e baseline conservadora do sandbox AI-Jail |
| Ainda não implementado | persistência pelo endpoint, constraints de negócio do banco, consulta HTTP, RabbitMQ, `processamento-service`, imagem da aplicação, Kubernetes e CD |

O estado técnico detalhado e as evidências red/green estão em [`spec.md`](spec.md). A única próxima tarefa fica em [`task.md`](task.md).

## O problema de engenharia estudado

Uma transação financeira fictícia entra no sistema, é registrada como `PENDENTE`, segue para processamento e, posteriormente, chega a um estado final consultável. Esse fluxo cria um contexto pequeno para estudar problemas relevantes para empresas que operam sistemas distribuídos:

- como manter o serviço de entrada disponível quando o processamento demora ou fica indisponível;
- como lidar com mensagens duplicadas e entrega pelo menos uma vez;
- como evitar a perda de um evento entre banco e mensageria;
- como recuperar falhas sem produzir estados inconsistentes;
- como diagnosticar o caminho de uma transação por logs, métricas e traces.

O CredPay não processa dinheiro real e não integra PIX, cartões ou instituições financeiras.

### Por que o fluxo planejado é assíncrono?

O cliente precisa receber rapidamente a confirmação de que o pedido foi aceito, mas a decisão final pode acontecer depois. Separar entrada e processamento por eventos reduz o acoplamento entre serviços e permite absorver picos ou indisponibilidades temporárias.

Essa escolha também traz custos que o projeto pretende tornar visíveis: consistência eventual, duplicação, ordenação, retries, DLQ e observabilidade. O objetivo não é afirmar que assíncrono é sempre melhor. Validações imediatas e a resposta de aceitação continuam síncronas; o processamento posterior é que será desacoplado.

## O que existe hoje

O `transacoes-service` contém o scaffolding executável e um domínio propositalmente mínimo:

```text
transacoes-service/
├── src/main/java/br/com/credpay/transacoes/
│   ├── TransacoesServiceApplication.java
│   ├── api/TransacaoController.java
│   ├── api/TransacaoExceptionHandler.java
│   ├── api/JacksonConfiguration.java
│   ├── application/
│   │   ├── CriarTransacao.java
│   │   ├── CriarTransacaoService.java
│   │   └── TransacaoRepository.java
│   ├── infrastructure/persistence/
│   │   ├── TransacaoEntity.java
│   │   └── TransacaoJpaRepository.java
│   └── domain/
│       ├── StatusTransacao.java
│       └── Transacao.java
├── src/test/java/br/com/credpay/transacoes/
│   ├── TransacoesServiceApplicationTest.java
│   ├── api/TransacaoControllerTest.java
│   ├── api/TransacaoHttpTest.java
│   ├── application/CriarTransacaoServiceTest.java
│   ├── domain/TransacaoTest.java
│   └── infrastructure/
│       ├── PostgresRuntimeTest.java
│       └── persistence/TransacaoRepositoryIntegrationTest.java
├── src/main/resources/db/migration/V1__create_transacoes.sql
├── mvnw
├── mvnw.cmd
└── pom.xml
```

Regras comprovadas até aqui:

1. uma transação válida nasce `PENDENTE`;
2. valor igual a zero é rejeitado;
3. valor negativo é rejeitado;
4. valor nulo é rejeitado com erro de domínio explícito;
5. moeda nula é rejeitada com erro de domínio explícito;
6. valor e moeda validados são conservados pela transação e usados no resultado da criação, sem arredondamento;
7. o UUID recebido pelo domínio é obrigatório e imutável, sendo reutilizado no resultado da criação.

O endpoint de criação possui adaptador MVC e um caso de uso mínimo. O UUID pertence ao domínio. Já existe um repository JPA com migration Flyway, testado contra PostgreSQL real, mas ele ainda não foi conectado ao caso de uso HTTP. Portanto, as transações criadas pelo endpoint continuam não persistidas. Consulta HTTP, eventos e timestamp permanecem futuros.

O teste de repository comprova duas operações separadas: gravação com commit e leitura em outro contexto, preservando UUID, valor, escala, moeda e `PENDENTE`. As constraints de negócio do banco e os cenários de falha ainda estão em desenvolvimento.

## Executando o estado atual

Pré-requisito da aplicação: JDK 21. Para executar a suíte completa (`test`, `package` ou `verify`), também é necessário Docker com engine Linux acessível. Na primeira execução, Maven e Testcontainers precisam de acesso aos repositórios para baixar dependências e imagens. No Windows, inicie o Docker Desktop e aguarde o engine ficar pronto.

No PowerShell:

```powershell
cd transacoes-service
.\mvnw.cmd --batch-mode --no-transfer-progress verify
.\mvnw.cmd spring-boot:run
```

Com a aplicação ativa, em outro terminal:

```powershell
Invoke-RestMethod http://localhost:8080/actuator/health

Invoke-RestMethod `
  -Method Post `
  -Uri http://localhost:8080/transacoes `
  -ContentType 'application/json' `
  -Body '{"valor":10.00,"moeda":"BRL"}'
```

Em Linux ou macOS, use `./mvnw` no lugar de `.\mvnw.cmd`. O `POST /transacoes` retorna `201 Created`, `Location` e uma representação `PENDENTE`, mas ainda não persiste o recurso.

O perfil padrão mantém a aplicação sem DataSource. O perfil opcional `persistencia`, usado pelo teste de repository, habilita JPA/Flyway e exige uma conexão PostgreSQL configurada; ativá-lo não conecta automaticamente o endpoint ao repository. Nos testes, o container e as propriedades de conexão são gerenciados automaticamente.

## Arquitetura planejada

O monorepo terá dois aplicativos Spring Boot independentes, cada um responsável por seu build, configuração, modelo e dados.

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

1. `transacoes-service` valida e persiste uma transação `PENDENTE`;
2. publica `TransacaoCriada` de forma confiável;
3. `processamento-service` consome e decide o resultado;
4. publica `TransacaoProcessada`;
5. `transacoes-service` atualiza o estado consultável;
6. sinais observáveis permitem acompanhar e diagnosticar o fluxo.

Essa arquitetura ainda é um alvo. O projeto não apresenta componentes planejados como se já estivessem prontos.

## Práticas de engenharia

### TDD em incrementos pequenos

Comportamentos de negócio seguem o ciclo red → green → refactor:

1. escrever o menor teste para um comportamento observável;
2. confirmar que ele falha pelo motivo esperado;
3. implementar somente o necessário para passar;
4. executar o teste focado e toda a suíte afetada;
5. registrar resultado, limite e próximo passo.

Os ciclos já executados e suas falhas esperadas estão registrados em [`spec.md`](spec.md#7-modelo-e-regras-implementadas).

### CI como controle evolutivo

O workflow atual executa Maven `verify` em pull requests relevantes e em mudanças do serviço na `main`. Ele valida compilação, testes e geração do JAR num runner Linux com Java 21. Também usa cache Maven, timeout, cancelamento de execuções obsoletas, permissões somente de leitura e actions externas fixadas por SHA.

O `verify` inclui PostgreSQL/Testcontainers, aplicação da migration Flyway e teste de round-trip do repository. O CI crescerá quando surgirem riscos concretos: constraints e falhas de persistência, RabbitMQ, contratos, qualidade estática e imagem de container. CD ainda não existe; será definido apenas quando houver uma imagem, um ambiente de destino e uma estratégia de rollback.

### Documentação como evidência

A documentação de processo é pública de propósito. Ela permite avaliar decisões, trade-offs, critérios de aceitação, testes, limitações e correções de percurso. A assistência de IA faz parte do processo, enquanto direção, escopo e decisões estruturais permanecem sob responsabilidade do autor.

## Segurança e AI-Jail

O projeto documentou um threat model para limitar o alcance de agentes e ferramentas durante o desenvolvimento. A baseline do `sandbox-core` prevê usuário não-root, recursos limitados, filesystem e mounts mínimos, nenhum Docker socket ou segredo do host e rede negada por padrão.

O sandbox ainda não foi implementado. Docker Desktop, daemon, VM, kernel/hypervisor e host permanecem parte da base confiável; portanto, nenhuma garantia de isolamento é apresentada como comprovada. O contrato, os testes negativos esperados e os riscos residuais estão em [`spec.md`](spec.md#41-modelo-de-ameaça-do-ai-jail).

## Roadmap

| Fase | Objetivo | Estado |
|---|---|---|
| 1 | governança, threat model e contrato do sandbox | documentação concluída; sandbox pendente |
| 2 | fundação reproduzível e CI mínimo | `transacoes-service` e CI implementados; restante pendente |
| 3 | regras de domínio em TDD e primeira integração PostgreSQL | em andamento |
| 4 | API, fluxo assíncrono, idempotência, outbox, retry e DLQ | planejada |
| 5 | experimentos de falha e resiliência | planejada |
| 6 | Kubernetes local e observabilidade | planejada |
| 7 | evolução do CI, CD e roteiro de demonstração | planejada |

O plano detalhado e os critérios de saída estão em [`CREDPAY_PLAN.md`](CREDPAY_PLAN.md).

## Documentação do projeto

| Documento | Papel |
|---|---|
| [`CLAUDE.md`](CLAUDE.md) | acordo de trabalho, autonomia, segurança e protocolo dos incrementos |
| [`CREDPAY_PLAN.md`](CREDPAY_PLAN.md) | visão, arquitetura-alvo, fases e controle de escopo |
| [`spec.md`](spec.md) | fonte de verdade técnica, ADRs, contratos e evidências atuais |
| [`task.md`](task.md) | próximo passo único |
| [`skills/`](skills/) | guias repetíveis para TDD, endpoints e testes de integração |

## Escopo e uso

CredPay é um estudo educacional. Frontend, autenticação, PIX, cartão, antifraude real, transação distribuída, multi-região e alta disponibilidade de produção estão fora da v1.

O repositório ainda não declara uma licença. Até que uma licença seja adicionada explicitamente, o conteúdo pode ser lido e avaliado, mas não há concessão automática de direitos de reutilização ou distribuição.
