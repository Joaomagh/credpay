# CredPay — Backlog do MVP

Atualizado em 2026-10-03. Fonte da direção: [plano](../CREDPAY_PLAN.md). Fatos e evidências: [spec](../spec.md). Uma única ação executável: [task](../task.md). Papéis: [P.O.](roles/product-owner.md), [dev sênior](roles/senior-developer.md) e [Scrum Master](roles/scrum-master.md).

## Prioridades

| ID | Resultado | Aceite de saída | Dependências | Estado |
|---|---|---|---|---|
| B01 | Contrato de processamento idempotente | identidade, equivalência/conflito, resultado, atomicidade e ack documentados; matriz de falhas revisada | contratos atuais | Contrato definido; resultado/outbox e ack após commit implementados; falhas operacionais em B04 |
| B02 | Resultado durável e processamento idempotente | PostgreSQL comprova resultado único, replay estável, conflito sem sobrescrita, rollback e concorrência | B01 e baseline própria de persistência | Concluído; B02.1–B02.6 comprovados no CI |
| B03 | Saída `TransacaoProcessada` confiável | contrato versionado, resultado + outbox atômicos, publicação confirmada e reenvio com a mesma identidade | B02 | Concluído; B03.1–B03.10 comprovados |
| B04 | Consumo seguro de `TransacaoCriada` | entrada validada, commit antes do ack, reentrega sem nova decisão, falhas limitadas/DLQ em PostgreSQL + RabbitMQ reais | B02 e B03; não ativar sem intenção de saída durável | Concluído; B04.1–B04.12 comprovados, ativação manual exige conferência |
| B05 | Estado final consultável e auditável | POST → eventos → GET conclui; duplicatas não duplicam histórico; transições inválidas não sobrescrevem estado | B03 e B04 | B05.1–B05.4c validados; parser B05.5a próximo |
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

**B04.2 — Topologia de entrada — concluído com limite.** Filas quorum, DLX e bindings opt-in declarados pelo processador; política escopada aplicada no teste, roteamento e entrega após liberar vaga da DLQ observados no [CI #106](https://github.com/Joaomagh/credpay/actions/runs/37062544845). Sem listener. A retenção sob recusa efetiva, a recuperação de rota ausente e a política operacional ainda não foram comprovadas, portanto consumo permanece desativado.

**B04.3 — Validação da entrada — concluído.** Parser sem listener valida envelope, tipos e propriedades AMQP; rejeições não incluem payload no erro. Red/green local e [CI #110](https://github.com/Joaomagh/credpay/actions/runs/37064493609) verde com 99 testes.

**B04.4 — Falha do destino de dead-lettering — concluído para rota ausente.** A mensagem rejeitada permaneceu na origem sem binding da DLQ e chegou com o mesmo payload depois da restauração no [CI #115](https://github.com/Joaomagh/credpay/actions/runs/37066089787). A espera de até 210 segundos reflete o retry interno; sem promessa de recuperação instantânea. Recusa efetiva por DLQ cheia e falha de nó não foram provadas. Sem listener ou política operacional.

**B04.5 — Ack após commit no caminho válido — concluído.** O listener opt-in chamou o caso de uso transacional; com a outbox pausada antes do commit, o [CI #121](https://github.com/Joaomagh/credpay/actions/runs/37069062896) observou banco invisível e entrega sem ack, depois resultado/outbox duráveis e fila vazia. A execução normal segue com consumidor desligado; sem retry, classificação de falhas ou política operacional.

**B04.6 — Replay equivalente pelo listener — concluído.** Duas publicações equivalentes foram observadas pelo listener no [CI #124](https://github.com/Joaomagh/credpay/actions/runs/37070642793); a segunda ficou sem ack enquanto era inspecionada e depois foi confirmada, sem outra decisão/outbox. Teste de caracterização que nasceu verde; não simula queda entre commit e ack.

**B04.7 — JSON inválido para DLQ — concluído.** O [CI #128](https://github.com/Joaomagh/credpay/actions/runs/37072605070) comprovou rejeição sem requeue (`x-first-death-reason=rejected`) e nenhum resultado/outbox novo. O red #127 gerou logs excessivos; detalhes e limite de evidência em `spec.md`. Conflito de aplicação e falhas transitórias ainda não são classificados.

**B04.8 — Conflito de identidade para DLQ — concluído.** Red unitário observado; [CI #133](https://github.com/Joaomagh/credpay/actions/runs/37095277698) verde com 106 testes e registro original completo preservado, corpo/identidade divergentes na DLQ e motivo `rejected`. Nenhum novo POM/dependência. Retry transitório, crash pós-commit e política operacional ficam em slices posteriores.

**B04.9 — Retry operacional limitado — concluído.** Red observado, 75 testes sem infraestrutura verdes e [CI #136](https://github.com/Joaomagh/credpay/actions/runs/37096399603) verde com 112 testes. Factory opt-in, três tentativas totais, esperas configuradas de 1 e 2 segundos e recoverer seguro sem dependência nova. PostgreSQL/RabbitMQ reais provaram rollback/recuperação e esgotamento. Exceções desconhecidas também recebem retry limitado; não é uma allowlist só de falhas transitórias.

**B04.10 — Perda de conexão depois do commit — concluído.** [CI #140](https://github.com/Joaomagh/credpay/actions/runs/37098555364), 113 testes verdes, comprovou banco durável/mensagem sem ack antes do fechamento real, reentrega pelo broker (`redelivered=true`), corpo/identidade iguais, snapshot/outbox completos intactos, uma linha de cada tabela e ack final sem DLQ. Advice da fixture fora da transação/retry; sem alteração de produção, process kill ou prova de HA.

**B04.11 — Recusa efetiva da DLQ cheia — concluído.** [CI #143](https://github.com/Joaomagh/credpay/actions/runs/37099496150), 113 testes verdes, comprovou rejeição do worker/destino em cenário controlado, retenção na origem, dois ocupantes mantidos antes da liberação e recuperação do mesmo corpo/identidade com origem vazia. Não observa diretamente o código `maxlen`, não prova HA/ausência de duplicatas. Diagnóstico interno seguro da versão fixada, somente fixture, cleanup das filas descartáveis. Nenhuma produção/dependência nova.

**B04.12 — Provisionamento mínimo verificável — concluído.** [CI #147](https://github.com/Joaomagh/credpay/actions/runs/37135722088) verde com 114 testes; [Secret Scan #12](https://github.com/Joaomagh/credpay/actions/runs/37135722077) verde no SHA final. [PR #93](https://github.com/Joaomagh/credpay/pull/93), `ef31015`, integrada. Artefato de duas políticas no vhost `/`, capacidades didáticas 10000/1000 e reject-publish; teste importa/reaplica o mesmo arquivo e preserva sentinelas residentes, conferindo tipo/argumentos/política/definição efetiva/flag e escopo. Runbook prepara e confere antes da ativação manual; não é bloqueio automático de startup nem defesa contra mudança posterior. Sem cluster, tuning ou implantação externa. Experimentos de nó/quorum/carga ficam em B06.

## Incrementos de B05

**B05.1 — Contrato de aplicação do resultado — definido documentalmente.** Seção 9.40 de spec: causa corresponde ao evento de criação local sem exigir published_at; primeira transição e recebimento/histórico atômicos; replay equivalente não escreve; conflito/transação desconhecida preservam estado. GET final e POST replay original PENDENTE devem coexistir sob teste HTTP. Precisão do instante recebido preservada, sem inbox redundante ou histórico inicial inventado. Revisão P.O./sênior, diff/UTF-8/links; nenhuma capacidade de runtime nova.

**B05.2 — Domínio final e reconstrução — integrado na PR #95.** TDD com reds observados para conclusão ausente, nova transição final e resultado inválido. CI #110/#111 e Secret Scan #17/#18 verdes, inclusive SHA final. Domínio preserva dados/original, recusa nova transição final e mapper não reinicia o estado. PostgreSQL real releu os dois snapshots finais após commit; não prova atualização de transação pendente. Sem listener, migration ou atualização de banco neste slice.

**B05.3 — Persistência da transição e recebimento — validado na PR #96.** Adapter transacional atualiza somente status e grava histórico completo; V5 conserva precisão epoch/nano, unicidade por evento/transação, FKs e checks semânticos. CI #118 verde com 129 testes, incluindo 21 cenários do adapter e rollback real por colisão na segunda escrita. Recusa preserva tipo próprio após descobrir tradução JPA inadequada; evidência em spec 9.42. Sem listener/dependência/backfill; integração exige checks no SHA final.

**B05.4a — Aplicação sequencial idempotente e causalidade — validado na PR #97.** TDD e refatoração verdes localmente; CI #121 passou 162 testes, incluindo 13 PostgreSQL de primeira aplicação/replay/conflitos/causa incorreta. Secret Scan #28 verde. Entrada tipada sem instante local externo, equivalência semântica e causa local sem published_at comprovados; retorna transição sem reler JPA após UPDATE JDBC. Sem listener/concorrência; spec 9.43 e checks do SHA final exigidos antes da integração.

**B05.4b — Concorrência real — validado na PR #98.** CI #126 passou 170 testes, incluindo seis disputas reais com advisory lock em espera por PID específico; equivalentes convergem, quatro conflitos preservam vencedora e rollback libera locks. Ordem/deduplicação das chaves efetivas protegidas por dois testes de regressão; 72 sem infraestrutura verdes pós-refatoração. Sem migration/dependência/listener; spec 9.44 e CI/scanner no último SHA exigidos antes do merge.

**B05.4c — Replay HTTP original — validado na PR #99.** Red CI #129 com quatro falhas esperadas; CI #130 verde com 174 testes e Secret Scan #37. POST equivalente conserva resposta/Location PENDENTE, GET final e dados/escala/histórico/outbox intactos nos dois finais. Mapper somente de resposta; sem UPDATE. Integração exige checks do SHA documental final; spec 9.45.

**B05.5a — Parser do resultado — validado na PR #100.** 79 focados/153 sem infraestrutura verdes; CI #133/Secret Scan #40 verdes. Envelope e propriedades AMQP validados, nanos/offset equivalentes preservados, extras compatíveis aceitos e rejeições sem payload/causa. Revisão encontrou conteúdo após JSON ignorado; red/green corrigiu com leitor local, sem mutar mapper. Sem banco/listener/dependência; spec 9.46 e checks no SHA final antes do merge.

**B05.5b — Topologia e provisionamento do retorno — validado no CI transações da PR #101.** CI #136 passou 257 testes/Secret Scan #43; broker real provou políticas efetivas/argumentos/flag/escopo v2, sentinelas residentes preservadas na reaplicação e roteamento nos dois bindings. Fila/DLQ quorum próprias, topologia padrão false, referência à exchange do processador sem apropriar declaração. Runbook/artefato próprios acompanhados pelo CI. Sem listener/dependência; revisão sem bloqueante, CI processador aplicável e checks do SHA final ainda exigidos para integrar; spec 9.47.

**B05.5c — Listener seguro do resultado — refinado, depende de b.** Aplicar duravelmente antes de ack; replay sem duplicar histórico; contrato/conflito/recusa permanente para DLQ; falha operacional com três tentativas limitadas. Aceite em PostgreSQL/RabbitMQ reais protege commit/ack, rejeição e esgotamento na nova fronteira. Não repetir todos os experimentos internos do broker nem habilitar consumo automaticamente.

**B05.5d — Fluxo vertical dos dois aplicativos — refinado, depende de c.** POST → ambas outboxes/eventos → GET final APROVADA/REJEITADA, com bancos próprios e execução real dos dois aplicativos. Aceite inclui evento duplicado, histórico único e replay POST original; saída fabricada pela fixture não comprova o fluxo. Refinar orquestração próxima da execução, sem dependência Java entre serviços. HA/tuning/dashboard/replay operacional da DLQ ficam fora de B05.

## Incrementos de B08

**B08.1 — Revisão de publicação segura — parcialmente concluída.** Baseline integrada na [PR #90](https://github.com/Joaomagh/credpay/pull/90): `.gitignore` comum, Gitleaks 8.30.1 (hashes em spec 16.1), hook pre-push ativo neste checkout e [Secret Scan #3](https://github.com/Joaomagh/credpay/actions/runs/37098282309) verde no SHA final, inclusive controle positivo com código exclusivo; três falsos positivos históricos iniciais e três routing keys públicas de B04.12 revisados por fingerprint. Nenhum segredo real confirmado. CI atua após o push; hook local contornável exige preparo em novos clones. Revisão complementar de logs/artefatos continua pendente. Reescrever histórico exige autorização específica; não inclui tornar o repo privado ou apagar documentos.

## Revisão e riscos

**B08.2 — Ciclo de vida das fixtures PostgreSQL — diagnóstico pendente.** CI #140 verde registrou 39 warnings Hikari de conexão já fechada. Investigar ordem de fechamento dos containers/contextos/pools e corrigir sob regressão real se comprovado. Aceite: contexto/containers encerrados corretamente, suíte completa verde e ausência dos warnings indevidos, sem silenciar logger ou reduzir teste. Não atribuir causa antes do diagnóstico; não impede B04.11 nem substitui revisão de segurança B08.1.

- Estado atual: B02 e B03 concluídos no CI; B04.1–B04.12 integrados, por último na PR #93; listener de entrada opt-in e conferência operacional obrigatória antes da ativação manual. Evidências e limites em `spec.md`.
- Para concluir o MVP do plano: retorno de `TransacaoProcessada`, transições finais e histórico idempotente (B05); recuperação/diagnóstico e definição de `FALHOU` (B06); ambiente reproduzível e Kubernetes local (B07); qualidade, revisão de publicação e roteiro de demonstração (B08). CD depende de artefato, destino e rollback definidos, sem autorização presumida para implantação externa.
- Sandbox AI-Jail continua apenas documentado. Retomar sua implementação como iniciativa delimitada; não alegar isolamento atual.
- Warning Mockito/Byte Buddy conhecido permanece; coordenar correção com evolução de qualidade, sem ocultá-lo.
- Testes locais com infraestrutura dependem do Docker disponível. CI pode fornecer evidência real, mas falha de infraestrutura não é red de negócio.
- Toda tarefa descoberta deve incluir problema observado, aceite e prioridade; não entra automaticamente na execução atual.
