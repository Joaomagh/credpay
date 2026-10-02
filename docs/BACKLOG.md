# CredPay — Backlog do MVP

Atualizado em 2026-10-02. Fonte da direção: [plano](../CREDPAY_PLAN.md). Fatos e evidências: [spec](../spec.md). Uma única ação executável: [task](../task.md). Papéis: [P.O.](roles/product-owner.md), [dev sênior](roles/senior-developer.md) e [Scrum Master](roles/scrum-master.md).

## Prioridades

| ID | Resultado | Aceite de saída | Dependências | Estado |
|---|---|---|---|---|
| B01 | Contrato de processamento idempotente | identidade, equivalência/conflito, resultado, atomicidade e ack documentados; matriz de falhas revisada | contratos atuais | Contrato definido; resultado/outbox implementados, listener/ack pendentes |
| B02 | Resultado durável e processamento idempotente | PostgreSQL comprova resultado único, replay estável, conflito sem sobrescrita, rollback e concorrência | B01 e baseline própria de persistência | Concluído; B02.1–B02.6 comprovados no CI |
| B03 | Saída `TransacaoProcessada` confiável | contrato versionado, resultado + outbox atômicos, publicação confirmada e reenvio com a mesma identidade | B02 | Concluído; B03.1–B03.10 comprovados |
| B04 | Consumo seguro de `TransacaoCriada` | entrada validada, commit antes do ack, reentrega sem nova decisão, falhas limitadas/DLQ em PostgreSQL + RabbitMQ reais | B02 e B03; não ativar sem intenção de saída durável | B04.1 contrato definido; B04.2 topologia próxima |
| B05 | Estado final consultável e auditável | POST → eventos → GET conclui; duplicatas não duplicam histórico; transições inválidas não sobrescrevem estado | B03 e B04 | Refinamento |
| B06 | Recuperação e diagnóstico demonstráveis | queda, atraso e duplicação testados; correlação, retry/DLQ e replay operacional observáveis | B05; refinar política de `FALHOU` | Refinamento |
| B07 | Ambiente local reproduzível | imagens/Compose e depois Kubernetes local com probes e recursos; roteiro demonstra fluxo e falha | B05 e B06 | Refinamento |
| B08 | Entrega e portfólio verificáveis | CI cobre riscos e imagens; CD só com destino/rollback definidos; README e demo coerentes | B07 e critérios do plano | Refinamento |

Não é uma promessa de uma PR por linha nem um cronograma. Itens grandes serão divididos por comportamento, mantendo dependências e evidências. Nenhum percentual de conclusão é inferido do número de PRs.

## Incrementos de B02

**B02.1 — Snapshot da decisão em domínio/aplicação — validado localmente e no CI.** O resultado conserva valor, moeda, limite e status; testes provam uma única consulta e preservação do snapshot após alteração da política. Continua sem banco, identidade de evento ou replay durável. Integração do PR encerra o slice.

**B02.2 — Baseline de persistência própria — concluída.** JPA foi escolhido para o agregado persistido; seis dependências/versões comprovadas foram aprovadas. Banco obrigatório e próprio, migration V1, constraints, precisão exata do instante recebido e primeiro round-trip estão definidos na seção 9.13 de `spec.md`. Não inclui AMQP, H2 ou listener.

**B02.3 — Primeiro round-trip PostgreSQL — concluído.** Dependências aprovadas, porta, adapter JPA, entidade e migration V1 foram adicionados em TDD. O teste usa commit/transações separadas e recompõe `occurredAt` por epoch second/nano. Compilação e 35 testes sem infraestrutura ficaram verdes localmente; o [CI Linux #34](https://github.com/Joaomagh/credpay/actions/runs/36802312464) executou o `verify` completo com sucesso.

**B02.4 — Integridade e rollback — concluído.** Três testes isolam colisões de `eventId`, `transactionId` e `outputEventId`, exigem a constraint correspondente e releem o record original completo. Outro teste executa `flush`, marca rollback e exige ausência numa nova transação. Como a V1 já possuía os controles, são testes de caracterização; o [CI Linux #37](https://github.com/Joaomagh/credpay/actions/runs/36802986688) executou o `verify` completo com sucesso.

**B02.5 — Idempotência sequencial na aplicação — concluído.** A primeira entrada válida decide e persiste; reentrega equivalente reutiliza o snapshot original sem consultar política, relógio ou gerador de identidade; divergência do mesmo evento e novo evento para transação concluída geram conflito sem sobrescrita. Nove testes novos de aplicação ficaram verdes localmente; o [CI Linux #40](https://github.com/Joaomagh/credpay/actions/runs/36904356924) executou `verify` com PostgreSQL real.

**B02.6 — Concorrência — concluído.** Duas chamadas simultâneas equivalentes convergem para uma única decisão persistida e retornam o mesmo snapshot; conflitos divergentes preservam o original. O [CI Linux #46](https://github.com/Joaomagh/credpay/actions/runs/36909956773) comprovou os cenários com PostgreSQL real e a regressão da ordem das chaves de lock. Não inclui consumidor ou outbox.

B02 só termina com evidências reais de durabilidade, rollback, conflitos e concorrência.

## Incrementos de B03

**B03.1 — Contrato de saída — concluído documentalmente.** O envelope `TransacaoProcessada` v1 reutiliza `outputEventId`, preserva causa/correlação e informa somente a transição final necessária ao primeiro serviço. Resultado e intenção de publicação deverão ser atômicos; registros preparatórios V1 receberão backfill verificável antes da publicação. Sem código ou AMQP neste incremento.

**B03.2 — Outbox própria — concluído.** Migration V2 cria a tabela e reconstrói a intenção dos resultados V1. O adapter JDBC insere e relê a intenção em PostgreSQL real; um teste de caracterização protege unicidade do `event_id`. Ainda não liga o caso de uso nem publica.

**B03.3 — Ligação transacional — concluído.** O caso de uso grava resultado e intenção no mesmo commit. O [CI #58](https://github.com/Joaomagh/credpay/actions/runs/36913236951) provou rollback após o INSERT da outbox e replay sem nova linha.

**B03.4 — Operações da outbox — concluído.** Leitura de pendências ordenada e limitada, com marcação idempotente comprovada no [CI #62](https://github.com/Joaomagh/credpay/actions/runs/36914275852). Não há chamador nem envio AMQP.

**B03.5 — Baseline RabbitMQ do resultado — concluído documentalmente.** Reutiliza versões e digest já testados no produtor; define exchange própria, propriedades da mensagem, confirms/returns e matriz de falhas, sem adicionar dependências ou publicar.

**B03.6 — Topologia da saída — concluído.** As duas dependências aprovadas e a exchange direct durável foram verificadas com RabbitMQ real no [CI #67](https://github.com/Joaomagh/credpay/actions/runs/36915844357). O health do broker permanece temporariamente desabilitado até a publicação tornar o broker necessário. Sem publicador, scheduler ou consumidor.

**B03.7 — Publicador isolado — concluído.** Payload/propriedades e confirmação correlacionada passaram em RabbitMQ real no [CI #71](https://github.com/Joaomagh/credpay/actions/runs/36916845797). O teste sem rota falhou no [CI #72](https://github.com/Joaomagh/credpay/actions/runs/36917064130); mandatory return corrigiu o comportamento e o [CI #73](https://github.com/Joaomagh/credpay/actions/runs/37052099553) passou com 69 testes. Ainda não há marcação da outbox.

**B03.8 — Ligação de uma pendência — concluído.** Uma chamada publica a primeira intenção e marca somente após `ack` sem return. No [CI #79](https://github.com/Joaomagh/credpay/actions/runs/37053459325), PostgreSQL e RabbitMQ reais comprovaram envio, marcação e ausência de reenvio após publicação. O [CI #80](https://github.com/Joaomagh/credpay/actions/runs/37053660495) comprovou pendência sem rota e recuperação com mesmo `eventId` e payload. Sem scheduler ou consumidor.

**B03.9 — Janela confirm/marcação — concluído.** Falha controlada após confirmação e antes de `published_at` deixa a intenção pendente; a nova tentativa entrega o mesmo `eventId`/payload e só então marca. [CI #83](https://github.com/Joaomagh/credpay/actions/runs/37054296624) verde com PostgreSQL e RabbitMQ reais, 72 testes. É caracterização do desenho existente, sem red artificial ou mudança de produção.

**B03.10 — Scheduler da saída — concluído.** Opt-in explícito, uma réplica publicadora, uma pendência por disparo e intervalo configurável. Indicador RabbitMQ ativo somente quando a publicação é habilitada. [CI #89](https://github.com/Joaomagh/credpay/actions/runs/37055939067) verde com 76 testes; sem consumidor ou eleição entre réplicas.

**B04.1 — Contrato do consumidor — concluído documentalmente.** A seção 9.28 de `spec.md` define fila/binding, validação do envelope e propriedades AMQP, ack após commit, classificação de erros, três tentativas transitórias e DLQ. O dead-lettering `at-least-once` exige política efetiva e teste; quorum sozinho não basta. Nenhum listener ou dependência foi criado.

**B04.2 — Topologia de entrada — próximo.** Declarar fila quorum e DLQ de propriedade do processador, binding ao produtor e política/limite de dead-lettering; provar comportamento com RabbitMQ real antes de qualquer listener.

## Revisão e riscos

- Estado atual: B02 e B03 concluídos no CI; B04.1 definido, B04.2 é a próxima implementação sem listener. Evidências em `spec.md`.
- Sandbox AI-Jail continua apenas documentado. Retomar sua implementação como iniciativa delimitada; não alegar isolamento atual.
- Warning Mockito/Byte Buddy conhecido permanece; coordenar correção com evolução de qualidade, sem ocultá-lo.
- Testes locais com infraestrutura dependem do Docker disponível. CI pode fornecer evidência real, mas falha de infraestrutura não é red de negócio.
- Toda tarefa descoberta deve incluir problema observado, aceite e prioridade; não entra automaticamente na execução atual.
