# CredPay — Backlog do MVP

Atualizado em 2026-09-30. Fonte da direção: [plano](../CREDPAY_PLAN.md). Fatos e evidências: [spec](../spec.md). Uma única ação executável: [task](../task.md). Papéis: [P.O.](roles/product-owner.md), [dev sênior](roles/senior-developer.md) e [Scrum Master](roles/scrum-master.md).

## Prioridades

| ID | Resultado | Aceite de saída | Dependências | Estado |
|---|---|---|---|---|
| B01 | Contrato de processamento idempotente | identidade, equivalência/conflito, resultado, atomicidade e ack documentados; matriz de falhas revisada | contratos atuais | Definido e revisado; implementação ainda pendente |
| B02 | Resultado durável e processamento idempotente | PostgreSQL comprova resultado único, replay estável, conflito sem sobrescrita, rollback e concorrência | B01 e baseline própria de persistência | Em andamento; B02.1 e B02.2 concluídos, B02.3 em validação no CI |
| B03 | Saída `TransacaoProcessada` confiável | contrato versionado, resultado + outbox atômicos, publicação confirmada e reenvio com a mesma identidade | B02 | Refinamento |
| B04 | Consumo seguro de `TransacaoCriada` | entrada validada, commit antes do ack, reentrega sem nova decisão, falhas limitadas/DLQ em PostgreSQL + RabbitMQ reais | B02 e B03; não ativar sem intenção de saída durável | Refinamento |
| B05 | Estado final consultável e auditável | POST → eventos → GET conclui; duplicatas não duplicam histórico; transições inválidas não sobrescrevem estado | B03 e B04 | Refinamento |
| B06 | Recuperação e diagnóstico demonstráveis | queda, atraso e duplicação testados; correlação, retry/DLQ e replay operacional observáveis | B05; refinar política de `FALHOU` | Refinamento |
| B07 | Ambiente local reproduzível | imagens/Compose e depois Kubernetes local com probes e recursos; roteiro demonstra fluxo e falha | B05 e B06 | Refinamento |
| B08 | Entrega e portfólio verificáveis | CI cobre riscos e imagens; CD só com destino/rollback definidos; README e demo coerentes | B07 e critérios do plano | Refinamento |

Não é uma promessa de uma PR por linha nem um cronograma. Itens grandes serão divididos por comportamento, mantendo dependências e evidências. Nenhum percentual de conclusão é inferido do número de PRs.

## Incrementos de B02

**B02.1 — Snapshot da decisão em domínio/aplicação — validado localmente e no CI.** O resultado conserva valor, moeda, limite e status; testes provam uma única consulta e preservação do snapshot após alteração da política. Continua sem banco, identidade de evento ou replay durável. Integração do PR encerra o slice.

**B02.2 — Baseline de persistência própria — concluída.** JPA foi escolhido para o agregado persistido; seis dependências/versões comprovadas foram aprovadas. Banco obrigatório e próprio, migration V1, constraints, precisão exata do instante recebido e primeiro round-trip estão definidos na seção 9.13 de `spec.md`. Não inclui AMQP, H2 ou listener.

**B02.3 — Primeiro round-trip PostgreSQL — implementação pronta, aceite remoto pendente.** Dependências aprovadas, porta, adapter JPA, entidade e migration V1 foram adicionados em TDD. O teste usa commit/transações separadas e recompõe `occurredAt` por epoch second/nano. Compilação e 35 testes sem infraestrutura estão verdes; Docker local indisponível torna o CI Linux obrigatório antes do merge.

**B02.4 — Integridade e rollback — próximo.** Comprovar separadamente que colisões de `eventId`, `transactionId` e `outputEventId` falham sem sobrescrever o primeiro resultado e que uma inserção revertida após `flush` não aparece numa nova transação. Não inclui replay de aplicação, concorrência, listener ou outbox.

B02 só termina com evidências reais de durabilidade, rollback, conflitos e concorrência.

## Revisão e riscos

- Estado atual: PRs até #62 integradas; PR #63 tem 36 testes verdes localmente e no CI, sem listener/banco/evento de saída. Evidências em `spec.md`.
- Sandbox AI-Jail continua apenas documentado. Retomar sua implementação como iniciativa delimitada; não alegar isolamento atual.
- Warning Mockito/Byte Buddy conhecido permanece; coordenar correção com evolução de qualidade, sem ocultá-lo.
- Testes locais com infraestrutura dependem do Docker disponível. CI pode fornecer evidência real, mas falha de infraestrutura não é red de negócio.
- Toda tarefa descoberta deve incluir problema observado, aceite e prioridade; não entra automaticamente na execução atual.
