# Imagens executáveis — startup e preparação dos dois serviços

Cada Dockerfile copia somente `target/<servico>-service-0.0.1-SNAPSHOT.jar`, previamente compilado no mesmo checkout. Não executa Maven nem instala pacotes. O nome exato faz o build falhar se o artefato faltar; atualizar a versão do projeto exige atualizar COPY e `.dockerignore`. O contexto permite somente Dockerfile, diretório target e esse JAR; fontes, `.env`, outros artefatos e configuração local ficam fora. Veja [contextos Docker](https://docs.docker.com/build/concepts/context/#dockerignore-files).

Runtime necessário ao plano: imagem oficial Eclipse Temurin `21.0.12.1_1-jre-jammy@sha256:f04fb34e053148344e83317976114ec3f37e4b830ec8bdab5a2fe3cecd7d010b`, índice verificado em 2026-10-04; manifest Linux/amd64 `sha256:8c2dddf1bb2a8455160f4e23080059de5003eddc5cb839130b177c6be0c2cfe0`. [Metadata da versão](https://hub.docker.com/v2/repositories/library/eclipse-temurin/tags/21.0.12.1_1-jre-jammy), [fonte do runtime](https://github.com/adoptium/containers/blob/47683eb1fa1b9576fe9d346f25e4158a5bbabf03/21/jre/ubuntu/jammy/Dockerfile). Primeira execução comprovada no [Images CI #1](https://github.com/Joaomagh/credpay/actions/runs/37177360363): ambos apps UID10001/healthUP, mounts0/privilegedfalse, exchanges produtoras e consumidores0; smoke1/26,26s. Digest fixo requer atualização deliberada futura.

`USER 10001:10001` define usuário/grupo numéricos. ENTRYPOINT em formato exec inicia Java diretamente; `CMD []` remove argumentos herdados. O agente Mockito é exclusivo do fork Surefire, não faz parte do JAR ou da imagem dos apps.

## Aceite do smoke

O workflow `CredPay Images CI` prepara ambos JARs e constrói duas imagens locais com tag igual ao SHA do checkout. `-DskipTests package` apenas prepara artefatos; suites completas e Flow continuam nos workflows próprios. `ImagensCredPayE2E`, fora da descoberta padrão, é selecionado explicitamente e não aceita pull das imagens dos aplicativos: tag ausente/inválida, imagem ausente ou Docker indisponível falham, sem skip ou fallback remoto.

O teste inicia dois PostgreSQL próprios e RabbitMQ nas baselines do projeto, conectados aos apps por aliases numa rede descartável. Usa credenciais fictícias explícitas; nenhum segredo é incorporado à imagem. Todas as fases exigem HTTP200/JSON `status=UP` nos dois `/actuator/health`, health Rabbit habilitado, UID efetivo10001 por `id -u`, usuário configurado, mounts vazios e privileged=false.

A ampliação B07.2 está escrita e aguarda prova no CI. Seu contrato de preparação mantém bancos/broker entre as fases e reinicia somente apps:

1. Topologias/consumo/publicação false: duas exchanges produtoras direct/duráveis, zero filas e consumidores.
2. Topologias true, consumo/publicação false: importar os dois arquivos de `infra/rabbitmq` no broker descartável. Conferir quatro filas quorum/duráveis, argumentos exatos, nomes/definições efetivas das políticas e ausência de operator policy; at-least-once/reject-publish/caps10000/1000, flag stream_queue e quatro exchanges/bindings. Consumidores zero antes de ativar.
3. Opt-in completo: polling limitado10s exige dois consumidores exatos nas entradas, ack obrigatório, prefetch10/1 e nenhum consumidor nas DLQs. JDBC só lê schemas: cada app possui sua tabela e não possui a tabela do outro agregado.

Network é declarado primeiro no try-with-resources externo; apps ficam em try interno por fase e são registrados antes do start. Cleanup encerra ambos antes da fase seguinte; ao terminar/falhar, encerra broker/bancos e rede, inclusive em startup parcial. Só remove recursos criados pelo teste, sem prune ou alvo global. Não imprimir inspect/env/logs integrais; diagnóstico deve preservar apenas o trecho necessário. O workflow não publica logs como artefatos nem envia imagens para registry.

Startup foi comprovado em B07.1; preparação/ativação ampliadas só terão aceite após CI verde. O teste não envia POST nem prova fluxo financeiro nas imagens, Compose, Kubernetes, HA, scanner de vulnerabilidade ou deploy. O Flow atual comprova o fluxo financeiro em dois processos JAR separados. Daemon/Testcontainers/runner são parte da base de confiança; apps não recebem Docker socket, workspace ou home montados. Rede bridge não é allowlist de egress e esse ambiente não implementa AI-Jail.

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
