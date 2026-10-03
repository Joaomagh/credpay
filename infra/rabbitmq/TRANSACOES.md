# Preparação do retorno TransacaoProcessada v1

Broker local didático RabbitMQ 4.3.5, vhost `/`, mesma baseline/digest dos testes. O arquivo [transacoes-policies.json](transacoes-policies.json) contém duas políticas próprias do retorno; não cria usuários, credenciais, vhosts, filas ou exchanges. Ele pode coexistir com [processamento-policies.json](processamento-policies.json): nomes e padrões são distintos. Não alterar broker externo com este roteiro.

| Fila | Política | max-length | overflow |
|---|---|---:|---|
| `credpay.transacoes.transacao-processada.v1` | `credpay-transactions-input` | 10000 | `reject-publish` |
| `credpay.transacoes.transacao-processada.dlq.v1` | `credpay-transactions-dlq-limit` | 1000 | `reject-publish` |

Capacidades didáticas, em mensagens, sem teto de bytes/TTL/outboxes. Quorum admite overshoot em trânsito; limite não é teto estrito. DLQ cheia pode reter mensagens na origem e recusar novas publicações; intenções não confirmadas permanecem nas outboxes. Não usar drop-head nem reduzir dead-lettering para at-most-once com pendências. HA/tuning e replay operacional ficam fora desta entrega.

## Preparar sem consumo

1. Configurar PostgreSQL próprio de cada serviço e conexões ao mesmo broker/vhost por ambiente, sem versionar valores reais. `SPRING_RABBITMQ_VIRTUAL_HOST=/` nos dois serviços.
2. Iniciar processador com limites por moeda e publicador desligado (`CREDPAY_OUTBOX_PUBLISHER_ENABLED=false`) para declarar sua exchange `credpay.processamento.v1`. O primeiro serviço apenas referencia esta exchange no binding.
3. Iniciar primeiro serviço com `CREDPAY_TRANSACOES_CONSUMER_TOPOLOGY_ENABLED=true`, `CREDPAY_TRANSACOES_CONSUMER_LISTENER_ENABLED=false` e publicador desligado. O listener experimental de B05.5c1 comprova somente caminho válido/replay; ativação operacional permanece proibida até rejeições permanentes e retry limitado de c2/c3, pois falhas ainda podem causar reentrega ilimitada. Aguardar filas/DLX/bindings; falha de declaração impede continuar.
4. Na raiz, copiar/importar o artefato no container local escolhido:

```powershell
$rabbitContainer = '<nome-do-broker-local>'
docker cp ./infra/rabbitmq/transacoes-policies.json "${rabbitContainer}:/tmp/transacoes-policies.json"
if ($LASTEXITCODE -ne 0) { throw 'Copia falhou; manter consumo desligado' }
docker exec $rabbitContainer rabbitmqctl import_definitions /tmp/transacoes-policies.json
if ($LASTEXITCODE -ne 0) { throw 'Importacao falhou; manter consumo desligado' }
```

Nomes estáveis, prioridade 10, `apply-to=quorum_queues`, regex ancoradas aos dois nomes exatos. Reaplicação é testada; não autoriza sobrescrever políticas conflitantes em broker desconhecido. Importação não remove recursos ausentes do arquivo.

## Conferir os recursos efetivos

Comandos exigem código 0. Exibem configuração/contagens, sem corpos de mensagem, usuários ou hashes de senha:

```powershell
docker exec $rabbitContainer rabbitmqctl --quiet --formatter=json list_feature_flags name state
docker exec $rabbitContainer rabbitmqctl --quiet --formatter=json list_policies -p /
docker exec $rabbitContainer rabbitmqctl --quiet --formatter=json list_operator_policies -p /
docker exec $rabbitContainer rabbitmqctl --quiet --formatter=json list_queues -p / name type durable arguments policy operator_policy effective_policy_definition
docker exec $rabbitContainer rabbitmqctl --quiet --formatter=json list_exchanges -p / name type durable
docker exec $rabbitContainer rabbitmqctl --quiet --formatter=json list_bindings -p / source_name destination_name destination_kind routing_key
docker exec $rabbitContainer rabbitmqctl --quiet --formatter=json list_consumers -p /
```

- `stream_queue=enabled`; ausência exige parar/revisar, sem ativação automática de flags.
- Cada fila pelo nome exato: quorum, durable=true, arguments exatamente `[["x-queue-type","longstr","quorum"]]` nesta CLI. Argumentos adicionais podem prevalecer sobre política e exigem revisão.
- policy corresponde à tabela; operator_policy vazio. Empate/maior prioridade/política concorrente exige investigar, sem apagar política ou recriar fila para passar.
- Definição efetiva da entrada exatamente `{"dead-letter-strategy":"at-least-once","overflow":"reject-publish","max-length":10000,"dead-letter-exchange":"credpay.transacoes.dlx.v1","dead-letter-routing-key":"transacao.processada.dlq.v1"}`; DLQ exatamente `{"max-length":1000,"overflow":"reject-publish"}`. Ordem JSON irrelevante.
- Exchanges `credpay.processamento.v1` e `credpay.transacoes.dlx.v1`: direct, duráveis. Bindings com destino fila: primeira → entrada por `transacao.processada.v1`; segunda → DLQ por `transacao.processada.dlq.v1`.
- Nenhum consumidor da aplicação nas duas filas durante preparação. Worker interno de dead-lettering não é listener da aplicação.

Guardar commit/hash do artefato/versão/conferência no registro local. Não exportar definições completas, pois podem conter hashes de senha. Inspeção não bloqueia startup automaticamente nem detecta mudanças posteriores. **Não habilitar listener experimental: aguardar os três aceites de B05.5c e a atualização deste roteiro.** Publicadores continuam exigindo uma réplica por serviço. Preparação de TransacaoCriada está em [README.md](README.md).

## Verificar em broker descartável

Na pasta `transacoes-service`, Java 21 e Docker Linux acessível:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress -Dtest=RabbitMqResultadoPoliticasIntegrationTest test
.\mvnw.cmd --batch-mode --no-transfer-progress verify
```

Fixture isolada de PostgreSQL/schedulers: importa arquivo real, confere tipo/argumentos/políticas/flag/escopo, preserva sentinelas residentes na reaplicação e observa roteamento nos dois bindings. Isso não prova rejeição de listener, crash/ack ou HA. Docker ausente é falha de ambiente, não teste aprovado. CI acompanha este runbook e artefato.
