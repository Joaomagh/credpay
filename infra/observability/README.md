# Diagnóstico local de publicação

Os dois serviços têm o perfil opcional `diagnostics`. Ele permite consultar `health`, `metrics` e `prometheus`; o comportamento padrão continua expondo apenas health. Não habilita env, beans ou configprops.

Em um terminal do serviço, com suas conexões e configurações já preparadas conforme o README principal, inicie uma instância local:

```powershell
./mvnw.cmd spring-boot:run '-Dspring-boot.run.profiles=diagnostics' '-Dspring-boot.run.arguments=--server.address=127.0.0.1'
```

Preserve os outros perfis necessários na lista de perfis de execução. O processador usa a porta8081 do roteiro principal; transações usa8080. O perfil não define endereço nem autenticação: a restrição ao loopback acima faz parte desta execução local. Não use esse comando para publicar a aplicação em rede externa. Compose e Kubernetes não são alterados por este recorte.

Após uma tentativa real de publicação, consulte o serviço correspondente:

```powershell
Invoke-RestMethod 'http://127.0.0.1:8080/actuator/metrics/credpay.messaging.publish.attempts'
Invoke-RestMethod 'http://127.0.0.1:8080/actuator/metrics/credpay.messaging.publish.attempts?tag=outcome:returned'
```

Para a saída do processador, substitua8080 por8081. Cada aplicativo tem seu próprio contador. Uma série só aparece após a primeira ocorrência daquele resultado; antes de qualquer tentativa o contador pode responder404. A ausência de uma série não prova que o fluxo está saudável.

| outcome | O que ocorreu na tentativa |
| --- | --- |
| confirmed | ACK do broker sem mensagem retornada |
| returned | Mensagem retornada sem rota, mesmo havendo ACK |
| nacked | Confirmação negativa do broker sem retorno |
| error | Falha de envio, confirmação excepcional, interrupção ou timeout |

`COUNT` soma tentativas desde o início do processo; reinícios reiniciam a medição em memória. Retries contam novamente. confirmed não comprova consumo, gravação de outbox ou conclusão da transação. Não há IDs de eventos, transações ou payload em etiquetas.

`metrics` também disponibiliza as métricas automáticas da JVM/HTTP existentes no aplicativo. O diagnóstico é destinado ao ambiente local restrito; não acrescenta autenticação, persistência das séries, painel ou SLO de produção. Os testes HTTP verificam o contrato com um registry real preparado; a prova de publicação/recuperação com PostgreSQL/RabbitMQ permanece nos testes dos publicadores.

## Exportação para Prometheus

O registry gerenciado pelo Boot apresenta o mesmo contador em `/actuator/prometheus`, somente com o perfil `diagnostics`. O nome exportado é `credpay_messaging_publish_attempts_total`, com a etiqueta fixa `outcome`. O endpoint padrão continua404. Séries automáticas JVM/HTTP também fazem parte dessa resposta; restringir o acesso como nos comandos acima.

No serviço local configurado, consulte `http://127.0.0.1:8080/actuator/prometheus` (8081 no processador). As séries de tentativas surgem após a primeira ocorrência; ausência não comprova zero ou saúde. Valores acumulam tentativas desde o início do processo e reiniciam com ele. Não indicam transações únicas, consumo ou commit. O backend fornece o formato de coleta; servidores Prometheus/Grafana foram adiados por João em16/10/2026 e não integram o aceite da v1. Não há envio push ou serviço externo configurado.

O experimento funcional está preservado na [PR154](https://github.com/Joaomagh/credpay/pull/154), branch `feat/observability-dashboard`, head38101da42ce56795664cee5645c227d542157d91. Observability8 comprovou coleta, painel por API e falha sem rota/recuperação causal; não comprovou renderização visual/acesso no host. Inventory117 recusou as imagens candidatas por achados HIGH e lacuna OS no Prometheus; nenhum código desses servidores foi incorporado à main. Retomada exige baseline/triagem/testes próprios, sem aceitar risco ou declarar os achados corrigidos pelo adiamento.

## Demo nos aplicativos do Compose

Após construir as imagens do checkout conforme o [quickstart Compose](../compose/README.md), use o switch explícito nas três fases:

```powershell
./infra/compose/demo.ps1 -Action Prepare -Diagnostics
./infra/compose/demo.ps1 -Action Activate -Diagnostics
./infra/compose/demo.ps1 -Action Demo -Diagnostics
```

O switch seleciona `compose.diagnostics.yaml` além do arquivo padrão. Ele substitui a lista de perfis ativos por `diagnostics` nos dois apps; não combina automaticamente outros perfis. O Compose padrão permanece sem esse perfil. `Prepare` e `Activate` recriam os apps conforme as mesmas verificações de topologia/consumo; não basta acrescentar o switch somente na leitura de uma instância já iniciada sem o perfil.

A demo confere lista de métricas200, lê baseline de confirmed, executa os dois resultados/replays existentes e faz polling por30s, com timeout de3s por requisição, buscando delta>=2 em cada aplicativo, antes de qualquer reinício. Não exige igualdade exata, porque retries também contam.404 da série filtrada vale zero somente depois de conferir que a lista de métricas está acessível. Respostas inválidas falham com mensagem segura.

O Smoke do CI usa projeto/volumes novos, sem publicações concorrentes externas, e depois conserva a prova de GET/replay/volumes após down/up. Não exige contador zero após reinício: outbox pendente pode ser recuperada. Esta prova não cobre returned/nacked/error via HTTP nem associa cada incremento a um evento específico. A publicação no host continua127.0.0.1; outros serviços na rede Compose podem acessar o endpoint interno. Não há autenticação acrescentada.

## Retorno sem rota nos JARs reais

O [roteiro causal descartável no Compose](../compose/DIAGNOSTICS.md) prepara esse diagnóstico no Windows: dois publicadores, returned por HTTP, outbox/GET pendentes e recuperação causal após restaurar a rota. Projeto, portas e volumes são próprios, sem alterar bindings do broker persistente de João. Exige revisão/CI/integração antes do ensaio; não declarar aceite local a partir dos controles offline.

A fixture FluxoCredPayE2E habilita diagnostics explicitamente e contém dois cenários adicionais, um por publicador. Retira somente o binding do broker descartável, consulta returned por HTTP e exige PENDENTE/outbox pendente íntegra. O finally restaura o binding; depois exige o evento original recuperado, decisão preservada e unicidade nas cinco tabelas. São seis casos no total; a prova remota passou no Flow CI84/run38019765157, com seis casos e zero falhas/erros/skips. Não cobre nack/error, crash abrupto ou HA.
