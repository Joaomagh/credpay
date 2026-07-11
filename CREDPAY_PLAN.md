# CredPay — Plano do Projeto

> Projeto **build to learn** para praticar Java backend, sistemas distribuídos, Kubernetes e observabilidade com XP e TDD. Este arquivo define direção e escopo. O `spec.md` registra o que foi decidido e construído; o `task.md` contém apenas o trabalho próximo.

## 1. Objetivo e critério de sucesso

CredPay simula um fluxo pequeno de processamento assíncrono de transações financeiras. Seu objetivo é gerar aprendizado demonstrável e evidências de engenharia para uma candidatura Java júnior/trainee em até cinco meses — não reproduzir um banco real.

O projeto estará pronto para portfólio quando João conseguir:

- explicar o fluxo ponta a ponta e os trade-offs sem depender da IA;
- demonstrar testes unitários, de integração e de falhas;
- provocar indisponibilidade/reentrega e observar recuperação, logs e métricas;
- subir o sistema localmente em Kubernetes seguindo o README;
- mostrar um pipeline reproduzível e um histórico de decisões e aprendizados.

### Fora do escopo da v1

- frontend, autenticação de usuários, PIX real, cartão real, antifraude real e dinheiro real;
- transação distribuída/2PC, multi-região e alta disponibilidade de produção;
- padrões ou ferramentas adicionados apenas para aumentar a lista do portfólio.

## 2. Fluxo de negócio v1

```text
POST /transacoes
  → transacoes-service valida e persiste PENDENTE
  → publica TransacaoCriada no RabbitMQ de forma confiável
  → processamento-service consome e decide APROVADA ou REJEITADA
  → publica TransacaoProcessada
  → transacoes-service atualiza o estado consultável
  → métricas, logs e traces permitem acompanhar o fluxo
```

Cada serviço é dono de seus dados. A consistência é eventual e a entrega da mensageria é tratada como **pelo menos uma vez**; portanto, consumidores precisam ser idempotentes.

### Regras iniciais

1. Uma transação nasce `PENDENTE`.
2. O valor monetário usa `BigDecimal`, deve ser maior que zero e ter moeda explícita.
3. O limite por transação é configurável; valor acima do limite resulta em `REJEITADA`.
4. Estados finais são `APROVADA`, `REJEITADA` e `FALHOU`; não retornam a `PENDENTE`.
5. A criação aceita uma chave de idempotência; a mesma chave e o mesmo pedido retornam o resultado anterior, enquanto payload diferente gera conflito.
6. Mudanças de estado guardam instante, estado anterior, novo estado e origem.

Detalhes e casos de borda só entram no `spec.md` quando tratados por uma história e seus testes.

## 3. Arquitetura e riscos que guiam o aprendizado

**Decisão:** monorepo com dois aplicativos Spring Boot independentes e bancos/schemas isolados.

```text
credpay/
├── transacoes-service/
├── processamento-service/
├── infra/
│   ├── agent-sandbox/
│   ├── compose/
│   ├── k8s/
│   └── observability/
├── skills/
├── CLAUDE.md
├── CREDPAY_PLAN.md
├── spec.md
└── task.md
```

O monorepo reduz manutenção para uma pessoa e facilita avaliação, sem eliminar fronteiras: cada serviço terá build, imagem, configuração, dados e responsabilidade próprios.

| Risco | Como será aprendido e demonstrado |
|---|---|
| Persistir e falhar antes de publicar | Testar e implementar Transactional Outbox quando o primeiro fluxo exigir confiabilidade |
| Mensagem entregue mais de uma vez | Consumidor idempotente e teste de reentrega |
| Processamento indisponível | Mensagem durável, retry limitado, DLQ e teste de recuperação |
| Estado divergente | Correlação, reconciliação observável e consistência eventual explícita |
| Falha silenciosa | Logs estruturados, métricas, alertas locais e correlation ID |
| Escopo crescente | v1 e critérios de saída definidos; frontend permanece fora do escopo |

> Resilience4j só será aplicado a chamadas síncronas que realmente existirem. Retry de consumo/publicação será configurado na camada adequada; não se adiciona circuit breaker apenas como palavra-chave.

## 4. Stack-alvo

| Área | Escolha | Intenção |
|---|---|---|
| Aplicação | Java 21, Spring Boot 3, Maven | Consolidar stack de vagas Java |
| Dados | PostgreSQL, Spring Data JPA, Flyway | Persistência real e migrations reproduzíveis |
| Mensageria | RabbitMQ, Spring AMQP | Entrega assíncrona, retry e DLQ |
| Testes | JUnit 5, AssertJ, Mockito, Testcontainers | Pirâmide de testes com infraestrutura real nas bordas |
| Contrato | Bean Validation, OpenAPI | API pequena e demonstrável |
| Observabilidade | Actuator, Micrometer, Prometheus, Grafana, logs estruturados | Diagnóstico por evidência |
| Plataforma | Docker Compose, Kubernetes local | Aprender deploy, configuração, probes e recursos |
| Entrega | GitHub Actions, Checkstyle e análise de dependências/imagem | Build e qualidade reproduzíveis |

Versões exatas são registradas no `spec.md` somente quando fixadas no build. Ferramentas opcionais não são compromisso antecipado.

## 5. Método de trabalho

Para cada incremento vertical pequeno:

1. João define/aceita a história e os critérios de aceitação.
2. O agente explica o desenho mínimo e riscos; João aprova decisões estruturais.
3. Escreve-se um teste pequeno e confirma-se a falha pelo motivo esperado (**red**).
4. Implementa-se o mínimo para passar (**green**).
5. Refatora-se com a suíte verde.
6. Atualizam-se `spec.md` e `task.md` apenas com evidências reais.

O Navigator decide direção e aprova decisões irreversíveis. O Driver pode investigar, oferecer opções e executar o incremento aprovado; não inventa funcionalidades nem ultrapassa a próxima porta de aprovação.

## 6. Plano por fases

As durações são estimativas, não prazos. Cada sessão deve produzir uma evidência pequena e verificável.

### Fase 1 — Governança e sandbox do agente

- importar e revisar os documentos-base no repositório;
- definir o modelo de ameaça e o que “isolado” significa;
- criar imagem/container não-root com apenas o workspace montado;
- restringir credenciais, capacidades, mounts e egress por mecanismo verificável;
- executar testes negativos e registrar limitações (Docker Desktop/host continuam parte da confiança).

**Saída:** regras aplicáveis, sandbox reproduzível e evidências de bloqueio — sem alegar isolamento que não foi testado.

### Fase 2 — Fundação reproduzível

- criar os dois módulos Spring Boot e a infraestrutura local;
- fixar versões e migrations iniciais;
- expor health checks sem regra de negócio;
- documentar comandos mínimos.

**Saída:** build e ambiente local reproduzíveis.

### Fase 3 — Primeiro incremento TDD

- escolher a menor regra (valor positivo/estado inicial);
- percorrer red → green → refactor;
- criar o primeiro teste de integração PostgreSQL com Testcontainers.

**Saída:** primeiro slice testado, não uma grande suíte vermelha antecipada.

### Fase 4 — Fluxo assíncrono confiável

- criar transação e consultar estado;
- publicar/consumir eventos com contrato versionado;
- testar idempotência, outbox, reentrega, retry e DLQ conforme entrarem no fluxo.

**Saída:** fluxo ponta a ponta e cenários de falha automatizados.

### Fase 5 — Qualidade e resiliência baseada em falhas reais

- refatorar sob testes;
- medir e corrigir gargalos relevantes;
- executar experimentos de queda, atraso e duplicação;
- usar Resilience4j somente onde houver chamada síncrona justificável.

**Saída:** relatório curto de experimentos e comportamento de recuperação.

### Fase 6 — Kubernetes e observabilidade

- aprender e aplicar Deployment, Service, ConfigMap/Secret, probes e recursos;
- subir o sistema em cluster local;
- criar painel com throughput, latência, aprovação/rejeição, erros, retries e DLQ;
- demonstrar diagnóstico de uma falha usando sinais observáveis.

**Saída:** demo local repetível e dashboard útil.

### Fase 7 — CI/CD e portfólio

- pipeline de build, testes, qualidade e imagens;
- README com arquitetura, quickstart, decisões, limitações e roteiro de demo;
- preparar uma explicação de 3 minutos e perguntas de entrevista baseadas no projeto.

**Saída:** repositório avaliável por recrutador e tecnicamente defensável.

## 7. Controle de escopo

Ao final de cada sessão: registrar uma evidência produzida, uma dúvida aprendida e o próximo passo único. Se o prazo apertar, reduzir funcionalidades; nunca pular teste, confiabilidade do fluxo principal ou capacidade de explicar o que foi construído.
