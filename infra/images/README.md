# Imagens executáveis — startup e preparação dos dois serviços

Cada Dockerfile copia somente `target/<servico>-service-0.0.1-SNAPSHOT.jar`, previamente compilado no mesmo checkout. Não executa Maven nem instala pacotes. O nome exato faz o build falhar se o artefato faltar; atualizar a versão do projeto exige atualizar COPY e `.dockerignore`. O contexto permite somente Dockerfile, diretório target e esse JAR; fontes, `.env`, outros artefatos e configuração local ficam fora. Veja [contextos Docker](https://docs.docker.com/build/concepts/context/#dockerignore-files).

Runtime atual B08.6c: Eclipse Temurin `21.0.12.1_1-jre-noble@sha256:000fd431958bc81a24abe1e8e5f0f0fd3ae365a594bd50aadb20696805f9408c`, manifest Linux/amd64 `sha256:7fd597bf48c8bb13a7a3fb227f8366dec0423f78775d4b4f7e30409204af8627`. [Metadata](https://hub.docker.com/v2/repositories/library/eclipse-temurin/tags/21.0.12.1_1-jre-noble), [fonte fixada](https://github.com/adoptium/containers/blob/511f9356dc4d50932a0a5f8cfb0f87ed1aef4f07/21/jre/ubuntu/noble/Dockerfile). Dois Dockerfiles usam o mesmo índice; não instalam pacotes. Baseline, SBOM e inventário em spec§9.94. Smoke também confere releases exatas libssl3t64/openssl3.0.13-0ubuntu3.16 via dpkg-query nos containers dos apps; prova runtime depende do Images CI do head correspondente.

Histórico B07.1: runtime original Temurin21JREJammy/digestf04fb34, substituído em B08.6c. O [Images CI #1](https://github.com/Joaomagh/credpay/actions/runs/37177360363) comprovou aquela baseline inicial: ambos apps UID10001/healthUP, mounts0/privilegedfalse, exchanges produtoras e consumidores0; smoke1/26,26s. Digest fixo requer atualização deliberada futura.

`USER 10001:10001` define usuário/grupo numéricos. ENTRYPOINT em formato exec inicia Java diretamente; `CMD []` remove argumentos herdados. O agente Mockito é exclusivo do fork Surefire, não faz parte do JAR ou da imagem dos apps.

## Aceite do smoke

O workflow `CredPay Images CI` prepara ambos JARs e constrói duas imagens locais com tag igual ao SHA do checkout. `-DskipTests package` apenas prepara artefatos; suites completas e Flow continuam nos workflows próprios. `ImagensCredPayE2E`, fora da descoberta padrão, é selecionado explicitamente e não aceita pull das imagens dos aplicativos: tag ausente/inválida, imagem ausente ou Docker indisponível falham, sem skip ou fallback remoto.

O teste inicia dois PostgreSQL próprios e RabbitMQ nas baselines do projeto, conectados aos apps por aliases numa rede descartável. Usa credenciais fictícias explícitas; nenhum segredo é incorporado à imagem. Todas as fases exigem HTTP200/JSON `status=UP` nos dois `/actuator/health`, health Rabbit habilitado, UID efetivo10001 por `id -u`, usuário configurado, mounts vazios e privileged=false.

A preparação B07.2 foi demonstrada no [Images CI #4](https://github.com/Joaomagh/credpay/actions/runs/37251745556): um smoke48,23s, seis startups non-root/healthUP e três fases conferidas no broker. Mantém bancos/broker entre as fases e reinicia somente apps:

1. Topologias/consumo/publicação false: duas exchanges produtoras direct/duráveis, zero filas e consumidores.
2. Topologias true, consumo/publicação false: importar os dois arquivos de `infra/rabbitmq` no broker descartável. Conferir quatro filas quorum/duráveis, argumentos exatos, nomes/definições efetivas das políticas e ausência de operator policy; at-least-once/reject-publish/caps10000/1000, flag stream_queue e quatro exchanges/bindings. Consumidores zero antes de ativar.
3. Opt-in completo: polling limitado10s exige dois consumidores exatos nas entradas, ack obrigatório, prefetch10/1 e nenhum consumidor nas DLQs. JDBC só lê schemas: cada app possui sua tabela e não possui a tabela do outro agregado.

B07.3 acrescenta, após essa preparação, dois casos financeiros nas imagens (50.000/APROVADA e150.000/REJEITADA, limite100.00 BRL), comprovados no [Images CI #7](https://github.com/Joaomagh/credpay/actions/runs/37253250486), smoke1/75,84s. POST201/PENDENTE/Location deve alcançar GET final; polling separado exige ambas outboxes publicadas. Leitura dos cinco conjuntos persistidos confere identidade/causa/correlação, resultado/dados/escala3 e instantes, unicidade e filas sem ready/unacked. Replay do POST com mesma chave/decimal equivalente deve conservar corpo/Location originais PENDENTE, GET final e snapshot completo. O teste usa HTTP no app real e somente SQL de leitura; não fabrica resultado ou evento.

Assert de JSON/map/valor/snapshot usa mensagem fixa para não despejar conteúdo financeiro na falha. Marcadores de execução imprimem somente estado e conclusão. GET final pode anteceder marcação da outbox de saída; a espera das duas publicações precede o snapshot. Esse snapshot é observado após quiescência do ambiente isolado, sem promessa de transação distribuída.

Network é declarado primeiro no try-with-resources externo; apps ficam em try interno por fase e são registrados antes do start. Cleanup encerra ambos antes da fase seguinte; ao terminar/falhar, encerra broker/bancos e rede, inclusive em startup parcial. Só remove recursos criados pelo teste, sem prune ou alvo global. Não imprimir inspect/env/logs integrais; diagnóstico deve preservar apenas o trecho necessário. O workflow não publica logs como artefatos nem envia imagens para registry.

Startup foi comprovado em B07.1; preparação/ativação foram conferidas no Images CI #4. Os casos financeiros B07.3 foram conferidos no Images CI #7: ambas publicações, histórico único e replay estável em cada resultado. O smoke não cobre recuperação/reentrega nas imagens, Compose, Kubernetes, HA, scanner de vulnerabilidade ou deploy. O Flow dos JARs permanece com quatro casos incluindo duplicatas, reinício e recuperação DLQ. Daemon/Testcontainers/runner são parte da base de confiança; apps não recebem Docker socket, workspace ou home montados. Rede bridge não é allowlist de egress e esse ambiente não implementa AI-Jail.

## Executar com Java21 e Docker Linux

Em `transacoes-service` e depois em `processamento-service`:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress -DskipTests package
```

Na raiz do repositório:

```powershell
$env:CREDPAY_IMAGE_TAG = git rev-parse HEAD
docker build --tag "credpay-transacoes:$env:CREDPAY_IMAGE_TAG" transacoes-service
docker build --tag "credpay-processamento:$env:CREDPAY_IMAGE_TAG" processamento-service
```

Em `transacoes-service`:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress '-DargLine=-XX:-EnableDynamicAgentLoading' -Dtest=ImagensCredPayE2E test
```

Linux usa `./mvnw` e `export CREDPAY_IMAGE_TAG=$(git rev-parse HEAD)`. Portas HTTP mapeadas são dinâmicas; configuração interna dos apps usa portas PostgreSQL5432/Rabbit5672 e aliases, sem localhost do host. Credenciais do smoke não servem como configuração operacional. Compilação local sem Docker não substitui o smoke real.
