# CredPay — Gestão de cobranças e acompanhamento de recebimentos

> Plano do produto e de sua evolução com XP e TDD. O `spec.md` distingue decisões de funcionalidades comprovadas; o `task.md` contém apenas o trabalho próximo. A base existente foi construída como simulador e ainda executa esse fluxo.

## 1. Objetivo e critério de sucesso

**Direção aprovada por João em 26/11/2026:** evoluir o CredPay para gestão de cobranças e acompanhamento de recebimentos de profissionais e pequenos negócios, sem limitar o produto a uma profissão. Uma nutricionista é o possível primeiro caso de uso; ainda não há entrevista, necessidade validada, piloto ou adoção comprovados.

O primeiro resultado de produto será permitir que uma conta organize clientes e cobranças, registre recebimentos manualmente e acompanhe o que está em aberto, vencido ou pago. O aprendizado e as evidências de engenharia continuam objetivos do projeto. A direção aprovada não significa que essas funcionalidades já existem.

### Primeiro MVP de cobranças

Jornada: entrar na conta → cadastrar cliente → criar cobrança com descrição, valor e vencimento → consultar situação → registrar recebimento integral manual → consultar histórico.

Recorte inicial de implementação:

- **Cliente:** identificador, conta proprietária e nome de exibição. Dados clínicos, CPF e contatos não são necessários a essa primeira jornada.
- **Cobrança:** identificador, conta proprietária, cliente da mesma conta, descrição, valor em BRL e vencimento como data de calendário. Valores usam `BigDecimal`, são positivos e têm no máximo duas casas decimais significativas; não há arredondamento silencioso. Isso é um contrato novo e não altera a precisão das transações legadas.
- **Recebimento manual:** integral e único por cobrança, com data do recebimento informada pelo usuário, autor e instante do registro. O sistema distingue a data informada do instante de auditoria. A indicação de paga significa recebimento registrado pelo usuário, sem confirmação bancária.
- **Situação:** `ABERTA` enquanto não há recebimento; `PAGA` após o registro. Na consulta, uma cobrança aberta com vencimento anterior à data atual aparece como `VENCIDA`; no próprio vencimento permanece aberta no prazo. A data atual usa relógio controlável e zona `America/Sao_Paulo`. O filtro de abertas inclui as vencidas; o filtro de vencidas seleciona somente esse subconjunto. Não persistir uma mudança diária de estado apenas para apresentar atraso.
- **Autorização:** clientes, cobranças e recebimentos pertencem à conta autenticada. Outra conta não pode consultar ou alterar os dados; o proprietário não é escolhido pelo corpo da requisição.
- **Repetição segura:** reenviar a criação da cobrança com a mesma chave e pedido preserva a cobrança original; payload diferente gera conflito. Repetir o mesmo registro de recebimento não duplica o registro; tentativa divergente não sobrescreve o anterior.

Exemplo de aceite com dados fictícios e relógio fixo em 26/11/2026: uma conta cadastra Cliente Exemplo e cria uma cobrança de R$ 250,00, descrição “Serviço de acompanhamento”, com vencimento em 25/11/2026. A consulta mostra vencida. Após registrar o recebimento integral com data de 26/11/2026, a consulta mostra paga e o histórico conserva vencimento, data informada, autor e instante do registro. A conta de outro usuário não acessa essa cobrança. O exemplo descreve o comportamento a implementar, não uma execução realizada.

Este recorte começa com recebimento integral único. Parcelamento, juros, descontos, recebimentos parciais, cancelamento e estorno precisam de refinamento próprio antes de entrar no produto. Correção de registro manual incorreto precisa de regra e testes antes de um piloto com dados reais. Não há prontuário, prescrição ou gestão clínica nesse MVP.

### Transição da base existente

- Preservar os contratos `/transacoes`, eventos v1, dados e cenários de recuperação existentes durante a evolução. Não converter transações históricas em cobranças.
- `APROVADA` continua sendo decisão do simulador por limite de valor; nunca equivale a `PAGA`. Novas cobranças não serão aprovadas pelo processador fictício.
- Começar clientes e cobranças no `transacoes-service`, com tabelas e contratos próprios. Reaproveitar autenticação e autorização; refinar as novas rotas antes do TDD HTTP. Não criar um terceiro serviço nem compartilhar dados entre os bancos por antecipação.
- Adicionar telas Angular conforme cada jornada existir na API. Não renomear os serviços ou reescrever o fluxo legado para aparentar uma entrega concluída.
- Provedor de pagamentos entra depois, começando por um único ambiente de testes e contrato próprio. Escolha do provedor, conta e credenciais não foram definidas. Dinheiro real, despesas, serviços externos, publicação e uso de dados reais continuam sujeitos a autorização específica.
- As pendências de banco, segurança, publicação e aceite pessoal da v1 continuam registradas. A nova direção não promove imagens, libera implantação ou declara concluído o candidato anterior.

### Critério de saída do MVP de cobranças

Uma conta percorre a jornada acima pela interface com API e persistência reais em ambiente de teste, com isolamento entre contas, criação idempotente, recebimento único, histórico e classificação de vencimento testados. Reinício preserva os registros. Evidências usam dados fictícios. A hipótese de utilidade será validada separadamente com uma pessoa do público; não inferir uso real de testes automatizados.

### Login OAuth2/OpenID Connect solicitado em 26/11

João pediu avaliar e implementar OAuth2/OIDC ao retomar a execução. O próximo refinamento de autenticação (C07) será após fechar a frente C01, sem trocar o login existente de forma parcial. OAuth2 define autorização delegada; OpenID Connect acrescenta autenticação. Proposta: login OIDC no Spring Security, com Authorization Code e PKCE, mantendo a sessão no backend e as permissões por conta no CredPay. Tokens não serão guardados no armazenamento do Angular.

Antes do incremento, escolher um provedor e registrar a baseline necessária. O vínculo entre a identidade externa (`issuer`, `subject`) e a conta local deve preservar a propriedade dos dados; não unir contas automaticamente por e-mail. Os testes deverão provar callback válido, rejeição de emissor/assinatura/audiência/nonce/estado inválidos, expiração e isolamento dos clientes. A definição não implementa OAuth2/OIDC nem autoriza criar conta externa, adicionar credencial real, contratar serviço ou publicar o sistema.

### Critérios de portfólio preservados

O projeto estará pronto para portfólio quando João conseguir:

- explicar o fluxo ponta a ponta e os trade-offs sem depender da IA;
- demonstrar testes unitários, de integração e de falhas;
- provocar indisponibilidade/reentrega e observar recuperação, logs e métricas;
- subir o sistema localmente em Kubernetes seguindo o README;
- mostrar um pipeline reproduzível e um histórico de decisões e aprendizados.

### Fora do escopo original da v1 backend — histórico preservado

- implementação de frontend, autenticação de usuários, PIX real, cartão real, antifraude real e dinheiro real;
- servidores de coleta Prometheus e painel Grafana, adiados por João em16/10/2026; preservar métricas/diagnósticos existentes e o experimento para etapa posterior;
- transação distribuída/2PC, multi-região e alta disponibilidade de produção;
- padrões ou ferramentas adicionados apenas para aumentar a lista do portfólio.
- sandbox do agente e remediação do core diagnóstico, separados da v1 do backend por decisão explícita de João em18/10/2026; pesquisa preservada e incompleta, sem aceite da baseline ou liberação de uso pelo agente.

### Briefing de frontend para etapa posterior — 2026-10-15

João considerou uma interface Angular com design explorado no Google Stitch e depois pediu priorizar o backlog, preservando somente o briefing. `docs/FRONTEND_BRIEF.md` guarda o recorte futuro: criar transação, acompanhar estado e consultar por ID usando contratos existentes. Frontend não é critério para encerrar esta v1; a preparação inicial não criou dependências, tela externa ou aplicação. João retomou explicitamente a implementação em22/10, após a primeira demo Compose local: frontend/ inicia a etapa Angular em paralelo ao acompanhamento dos aceites pessoais restantes, com uma única frente de implementação. Isso não torna frontend requisito retroativo nem declara encerrada a v1 backend.

## 2. Fluxo de negócio v1 — simulador existente

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

**Base existente:** monorepo com dois aplicativos Spring Boot independentes e bancos/schemas isolados. A transição de produto está em §1; o desenho abaixo descreve o fluxo legado.

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
├── AGENTS.md
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
| Escopo crescente | recorte do MVP de cobranças em §1; frontend original retomado em etapa própria, sem alterar retroativamente os aceites da v1 backend |

> Resilience4j só será aplicado a chamadas síncronas que realmente existirem. Retry de consumo/publicação será configurado na camada adequada; não se adiciona circuit breaker apenas como palavra-chave.

## 4. Stack-alvo

| Área | Escolha | Intenção |
|---|---|---|
| Aplicação | Java 21, Spring Boot, Maven | Consolidar stack de vagas Java; migração Boot4 autorizada em15/10 para correções públicas de segurança, baseline em spec§9.94 |
| Dados | PostgreSQL, Spring Data JPA, Flyway | Persistência real e migrations reproduzíveis |
| Mensageria | RabbitMQ, Spring AMQP | Entrega assíncrona, retry e DLQ |
| Testes | JUnit Jupiter, AssertJ, Mockito, Testcontainers | Pirâmide de testes com infraestrutura real nas bordas; versões alinhadas à plataforma em spec§9.94 |
| Contrato | Bean Validation, OpenAPI | API pequena e demonstrável |
| Observabilidade | Actuator, Micrometer e logs; Prometheus/Grafana adiados | Diagnóstico por evidência, sem painel obrigatório na v1 |
| Plataforma | Docker Compose, Kubernetes local | Aprender deploy, configuração, probes e recursos |
| Entrega | GitHub Actions, Checkstyle e análise de dependências/imagem | Build e qualidade reproduzíveis |

Versões exatas são registradas no `spec.md` somente quando fixadas no build. Ferramentas opcionais não são compromisso antecipado.

## 5. Método de trabalho

Para cada incremento vertical pequeno:

1. O P.O. refina a próxima história dentro da v1 aceita por João, com critérios verificáveis.
2. O dev sênior explica o desenho mínimo e riscos; o Scrum Master coordena a execução e a revisão, conforme autonomia de `AGENTS.md`.
3. Escreve-se um teste pequeno e confirma-se a falha pelo motivo esperado (**red**).
4. Implementa-se o mínimo para passar (**green**).
5. Refatora-se com a suíte verde.
6. Atualizam-se `spec.md` e `task.md` apenas com evidências reais.

O Navigator mantém a direção e aprova expansão de escopo e decisões irreversíveis. Dentro do plano, o ciclo pode seguir entre incrementos sem nova pergunta a cada tarefa. Uma entrega só termina com evidências e revisão; o backlog em `docs/BACKLOG.md` não substitui o próximo passo único de `task.md`. Não há promessa de agentes permanentes ou execução fora da sessão ativa.

## 6. Plano por fases da base original — histórico

As durações são estimativas, não prazos. Cada sessão deve produzir uma evidência pequena e verificável. A sequência de evolução do produto aprovada em 26/11 está no backlog de cobranças; as fases abaixo preservam o plano original e seus aceites.

### Fase 1 — Governança e sandbox do agente

**Recorte revisado por João em18/10/2026:** governança e restrições de trabalho permanecem aplicáveis. A implementação/remediação da sandbox passa a pesquisa posterior, fora do aceite de fechamento da v1 do backend. Os itens e a saída abaixo descrevem essa pesquisa ainda incompleta, não uma entrega concluída. Evidências, CI, fontes e findings ficam preservados; nenhuma candidata foi aceita ou liberada para uso pelo agente. Esta decisão não dispensa segurança, testes e inventários dos aplicativos nem altera as restrições de AGENTS.md.

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
- estabelecer CI mínimo para executar build e testes automaticamente em cada mudança relevante;
- documentar comandos mínimos.

**Saída:** build local e CI reproduzíveis, além de ambiente local documentado.

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
- usar os contadores existentes por HTTP para observar publicação e diagnosticar retorno sem rota/recuperação; não ampliar catálogo de métricas sem necessidade;
- demonstrar diagnóstico de uma falha usando sinais observáveis.

**Saída da v1 revisada em16/10/2026:** demonstração do fluxo e de uma falha com sinais HTTP e limites explícitos. João adiou os servidores Prometheus/Grafana; painel não é critério de fechamento desta v1. O experimento permanece na branch `feat/observability-dashboard`, head38101da42ce56795664cee5645c227d542157d91/PR154: prova funcional no CI, imagens reprovadas e nenhuma incorporação à main. Retomar somente com baseline/triagem/testes revisados; adiamento não declara os achados corrigidos nem aceita risco. Execução local/Kubernetes e ensaio por João conservam seus limites e pendências próprios.

### Fase 7 — Entrega, CD e portfólio

- evoluir o CI com verificações de qualidade e imagens;
- implementar CD somente quando houver artefato e destino de implantação definidos;
- README com arquitetura, quickstart, decisões, limitações e roteiro de demo;
- preparar uma explicação de 3 minutos e perguntas de entrevista baseadas no projeto.

**Saída:** repositório avaliável por recrutador e tecnicamente defensável.

## 7. Controle de escopo

Ao final de cada sessão: registrar uma evidência produzida, uma dúvida aprendida e o próximo passo único. Se o prazo apertar, reduzir funcionalidades; nunca pular teste, confiabilidade do fluxo principal ou capacidade de explicar o que foi construído.
