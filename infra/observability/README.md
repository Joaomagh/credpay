# Diagnóstico local de publicação

Os dois serviços têm o perfil opcional `diagnostics`. Ele permite consultar `health` e `metrics`; o comportamento padrão continua expondo apenas health. Não habilita env, beans ou configprops.

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

`metrics` também disponibiliza as métricas automáticas da JVM/HTTP existentes no aplicativo. O diagnóstico é destinado ao ambiente local restrito; não acrescenta autenticação, exportador, persistência, painel ou SLO de produção. Os testes HTTP verificam o contrato com um registry real preparado; a prova de publicação/recuperação com PostgreSQL/RabbitMQ permanece nos testes dos publicadores.
