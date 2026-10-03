# Preparação do consumidor de TransacaoCriada v1

Escopo: broker local de demonstração RabbitMQ **4.3.5**, no vhost **`/`**, com PostgreSQL próprio de cada serviço. O arquivo [processamento-policies.json](processamento-policies.json) contém somente duas políticas; não cria usuários, credenciais, vhosts, filas ou exchanges. As aplicações e a conferência precisam usar o mesmo broker/vhost. Outro vhost exige adaptação explícita do artefato e repetição da validação.

## Decisão de capacidade

| Fila | max-length | overflow | Política |
|---|---:|---|---|
| `credpay.processamento.transacao-criada.v1` | 10000 | `reject-publish` | `credpay-processing-input` |
| `credpay.processamento.transacao-criada.dlq.v1` | 1000 | `reject-publish` | `credpay-processing-dlq-limit` |

A entrada conserva a capacidade já usada nas fixtures. A DLQ usa uma baseline didática menor para tornar acúmulo de falhas visível; **não é dimensionamento de produção**. São limites em quantidade de mensagens, sem teto de bytes, prazo de retenção ou limite de armazenamento das outboxes. Quorum admite overshoot com mensagens em trânsito; `max-length` não é teto estrito. Sem TTL, descarte `drop-head` ou limpeza automática. DLQ cheia pode reter mensagens na origem e eventualmente recusar novas publicações; os publicadores preservam suas intenções não confirmadas na outbox. Referência: [limites e dead-lettering quorum](https://www.rabbitmq.com/docs/quorum-queues).

## Preparar com o listener desligado

1. Configure as conexões dos dois serviços no ambiente, sem versionar valores reais. Defina `SPRING_RABBITMQ_VIRTUAL_HOST=/` em ambos. Não altere um broker externo com este roteiro.
2. Inicie o `transacoes-service` com `CREDPAY_OUTBOX_PUBLISHER_ENABLED=false` para declarar `credpay.transacoes.v1`.
3. Inicie o `processamento-service` com limites por moeda e PostgreSQL próprios, `CREDPAY_PROCESSAMENTO_CONSUMER_TOPOLOGY_ENABLED=true`, `CREDPAY_PROCESSAMENTO_CONSUMER_LISTENER_ENABLED=false` e `CREDPAY_OUTBOX_PUBLISHER_ENABLED=false`. Aguarde a declaração das duas filas, da DLX e dos bindings; falha de declaração impede continuar.
4. Copie o artefato para o broker local escolhido e importe-o. Em PowerShell, na raiz do repositório, substitua apenas o nome do container:

```powershell
$rabbitContainer = '<nome-do-broker-local>'
docker cp ./infra/rabbitmq/processamento-policies.json "${rabbitContainer}:/tmp/processamento-policies.json"
if ($LASTEXITCODE -ne 0) { throw 'Copia falhou; manter listener desligado' }
docker exec $rabbitContainer rabbitmqctl import_definitions /tmp/processamento-policies.json
if ($LASTEXITCODE -ne 0) { throw 'Importacao falhou; manter listener desligado' }
```

O arquivo usa nomes estáveis, prioridade `10`, `apply-to=quorum_queues` e regex ancoradas aos dois nomes exatos. Reaplicar o mesmo arquivo é parte do teste; isso não autoriza sobrescrever políticas conflitantes em um broker desconhecido. A importação não remove recursos ausentes do arquivo. [Referência do comando](https://www.rabbitmq.com/docs/definitions).

## Conferir antes da ativação manual

Execute no mesmo container. Cada comando precisa concluir com código `0`; qualquer ausência ou divergência abaixo mantém o listener desligado. As consultas exibem configuração e contagens, sem corpos de mensagem, usuários ou hashes de senha.

```powershell
docker exec $rabbitContainer rabbitmqctl --quiet --formatter=json list_feature_flags name state
docker exec $rabbitContainer rabbitmqctl --quiet --formatter=json list_policies -p /
docker exec $rabbitContainer rabbitmqctl --quiet --formatter=json list_operator_policies -p /
docker exec $rabbitContainer rabbitmqctl --quiet --formatter=json list_queues -p / name type durable arguments policy operator_policy effective_policy_definition
docker exec $rabbitContainer rabbitmqctl --quiet --formatter=json list_exchanges -p / name type durable
docker exec $rabbitContainer rabbitmqctl --quiet --formatter=json list_bindings -p / source_name destination_name destination_kind routing_key
docker exec $rabbitContainer rabbitmqctl --quiet --formatter=json list_consumers -p /
```

Confira **cada fila pelo nome exato**, não por busca de palavras no conjunto:

- `stream_queue` deve estar `enabled`. Se não estiver, parar e revisar a preparação; este roteiro não habilita flags automaticamente.
- Ambas as filas devem ser `quorum`, `durable=true`, com `arguments=[["x-queue-type","longstr","quorum"]]` na saída JSON desta CLI (nome/tipo/valor AMQP). Argumentos `x-*` adicionais exigem revisão, pois podem prevalecer sobre a política.
- `policy` deve corresponder à tabela de capacidade e `operator_policy` deve estar vazio. Política concorrente de maior prioridade, empate ou operator policy exige investigação; não apagar políticas nem recriar filas para passar a conferência.
- `effective_policy_definition` da entrada deve ser exatamente `{"dead-letter-strategy":"at-least-once","overflow":"reject-publish","max-length":10000,"dead-letter-exchange":"credpay.processamento.dlx.v1","dead-letter-routing-key":"transacao.criada.dlq.v1"}`. A DLQ deve ser exatamente `{"max-length":1000,"overflow":"reject-publish"}`. Ordem das chaves JSON é irrelevante.
- `credpay.transacoes.v1` e `credpay.processamento.dlx.v1` devem ser exchanges `direct`, duráveis. Binding de entrada: `credpay.transacoes.v1` → fila de entrada, routing key `transacao.criada.v1`. Binding de DLQ: `credpay.processamento.dlx.v1` → DLQ, routing key `transacao.criada.dlq.v1`. Destinos devem ser filas.
- Não deve existir consumidor da aplicação na entrada ou DLQ durante a preparação. O worker interno de dead-lettering não é o listener da aplicação.

Guarde commit, hash do artefato, versão do broker e resultado da conferência no registro local da demonstração. Não exporte definições completas: podem conter hashes de senha.

Depois de conferir, reinicie somente uma instância do processador com topologia habilitada e `CREDPAY_PROCESSAMENTO_CONSUMER_LISTENER_ENABLED=true`. A publicação das duas outboxes continua opt-in e exige **uma réplica publicadora por serviço**. Isso permite operar a entrada; o retorno ao estado HTTP final ainda depende de B05. Inspeção não é bloqueio automático de startup, não detecta alterações posteriores e não prova HA. Não reduzir de `at-least-once` para `at-most-once`/`drop-head` com pendências: a mudança pode descartar dead letters retidas. Replay/remoção operacional da DLQ exige roteiro próprio de B06.

## Validar o artefato em broker descartável

Na pasta `processamento-service`, com Java 21 e Docker Linux acessível:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress -Dtest=RabbitMqPoliticasOperacionaisIntegrationTest test
.\mvnw.cmd --batch-mode --no-transfer-progress verify
```

O teste copia o mesmo arquivo do repositório, importa duas vezes, compara as definições efetivas por fila, tipo/argumentos/política/flag, mantém listener desligado e preserva mensagens sentinela na reaplicação. Também verifica que uma fila quorum fora dos padrões não recebe as políticas. As fixtures de falha anteriores mantêm DLQ de capacidade `1` para experimentos controlados; isso não altera a capacidade operacional `1000` deste artefato. CI inclui mudanças em `infra/rabbitmq/**`. Docker ausente é falha de ambiente, não teste aprovado.
