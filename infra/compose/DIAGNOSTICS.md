# Diagnóstico causal descartável por métricas

Roteiro para executar **somente após revisão, CI e integração** do launcher. João executa os comandos Docker no Windows/PowerShell7.3+; o agente não acessa o daemon local. Não é preciso habilitar Kubernetes, instalar ferramenta ou fornecer credencial real.

O experimento usa as mesmas quatro imagens linux/amd64 já construídas/carregadas: dois apps com tag SHA completa, PostgreSQL e RabbitMQ pinados no Compose. Não baixa nem recompila imagens automaticamente. Confere plataforma nativa do daemon/imagens e congela os IDs locais opacos antes/depois; não compara índice com config/CRI nem carrega imagens em outro runtime. Não exige seletor de plataforma API1.49; o percurso Kubernetes conserva seu guard próprio. O exemplo abaixo usa f412652, cujas fontes dos apps permanecem iguais; uma alteração de aplicativo exige nova preparação e tag correta, sem retaggear imagem antiga.

## Uma ação por vez

Na raiz do CredPay, execute:

```powershell
./infra/compose/diagnose.ps1 -Action Run -ImageTag f412652af18674c781f867d01240cf6012a35e4b
```

O launcher cria um projeto exclusivo `credpay-diag-<data>-<nonce>`, separado de `credpay-demo`. Usa somente contas fictícias, `.env.example`, configuração Docker vazia e ambiente dos filhos por allowlist. Não lê `.env` ou configuração Docker pessoal nem herda credenciais, proxies ou contexto alternativo. No Windows chama o Compose instalado pelo caminho absoluto ao lado do Docker Desktop, sem instalação global ou descoberta de plugin em diretório pessoal. HOME/cache/temp ficam na sessão própria do workspace. A conexão Docker é somente o endpoint Desktop Linux já conferido; clientes, daemon e VM continuam parte da confiança.

Duas portas altas são escolhidas em loopback; uma corrida de ocupação deve falhar sem usar outra porta/recurso silenciosamente. Opções TxPort/ProcPort existem para o CI/browser, mas devem ficar omitidas no ensaio local para preservar8080/8081. Rede própria `internal`, sem portas publicadas de banco/broker; apenas HTTP dos apps em127.0.0.1. Isso restringe a rede do experimento, sem alegar sandbox do agente ou uma allowlist completa de egress. Os apps continuam UID10001, sem mounts/privilégios/dispositivos e com capabilities removidas. Infraestrutura conserva seus volumes nomeados próprios; nenhum socket/home/workspace é montado.

## Evidência esperada

O Smoke prepara topologias antes de ativar consumidores e verifica as mesmas provas de demo/replay/persistência existentes. Acrescenta um cenário por publicador:

1. Confere health/metrics, filas drenadas e ausência de outboxes anteriores pendentes; registra baseline `returned` e identidade/início dos dois processos.
2. Remove somente o binding exato da exchange própria, confirma ausência de qualquer rota dessa exchange e cria um pedido fictício.
3. Exige aumento de `returned` por HTTP, GET PENDENTE e outbox bloqueada única com `published_at` nulo. Na entrada sem rota ainda não existe decisão; na saída sem rota a decisão já existe e deve permanecer igual. Nenhum histórico final pode surgir nessa fase.
4. Restaura e reconfere o binding em `finally`, inclusive se o POST ou uma asserção falhar. Restauração não comprovada aparece explicitamente; não prossegue como sucesso.
5. Exige APROVADA com o mesmo UUID, dados e evento original; payload/outbox preservados exceto publicação, causas/correlação entre criação/decisão/histórico, unicidade nas cinco tabelas, POST original por replay e filas drenadas.

Os resumos `Compose causal: publicador=...; returned delta=...` só aparecem após essas verificações. O delta deve ser pelo menos1, sem exigir igualdade: retries são novas tentativas. Contador não prova sozinho consumo/commit, nem associa etiquetas a uma transação; a causalidade vem das verificações SQL/HTTP separadas no projeto sem publicações externas.404 da série só significa zero depois de conferir o endpoint de métricas. Queda de contador ou mudança de processo invalidam o ensaio. Não cobre nack/error, crash abrupto, HA ou dinheiro real; fingerprints MD5 são apenas comparação de linhas, sem alegação de segurança criptográfica.

## Remoção explícita

Run preserva os recursos para revisão. Após conferir o resultado, o comando abaixo **descarta somente os dados fictícios deste experimento**, preservando `credpay-demo` e seus volumes:

```powershell
./infra/compose/diagnose.ps1 -Action Remove
```

O registro próprio congela daemon, nonce, tag, overlay e hashes dos dois arquivos Compose e `.env.example`. A remoção exige o conjunto registrado de containers/IDs, rede/ID e volumes/nome+instante de criação, labels e ausência de anexos externos; reconfere imediatamente antes do down e comprova ausência depois. Nunca usa prune ou remove-orphans. Preserva arquivos de evidência e remove somente o ponteiro de sessão após sucesso. Arquivos/host continuam confiáveis; os guards não são prova contra administrador malicioso nem eliminam todas as corridas.

Erro preserva sessão/recursos e informa fase/categoria fixa, sem despejar env, SQL, payload ou logs brutos. Não repetir Run, editar owner, mudar nome para CI ou apagar volumes para contornar recusa. Entradas Compose alteradas precisam de revisão antes da limpeza; não atualizar seus hashes manualmente. Interrupção abrupta antes de gravar inventário pode exigir diagnóstico de propriedade, sem adoção por nome.

## Verificação sem Docker

Controles em check-diagnostic-fault/local/lifecycle/process.ps1 e check-smoke-refusal.ps1 exercitam sequência causal, falhas/restauração, propriedade, adulteração de configuração, criação parcial, identidade de cleanup e ambiente/argumentos de um filho PowerShell inofensivo. O transporte Docker/HTTP é fictício, sem fallback nativo. O CI executa esses controles em Windows/Linux antes de executar o launcher com serviços reais e remover somente seu projeto próprio. Prova no CI não substitui o resultado executado por João.

Binding segue a representação e APIs de [rabbit_binding na versão4.3.5](https://github.com/rabbitmq/rabbitmq-server/blob/v4.3.5/deps/rabbit/src/rabbit_binding.erl); argumentos são constantes permitidas, sem interpolar dados de transação no código Erlang. O uso de configuração congelada considera a remoção de volumes pelos nomes do modelo no [Compose](https://github.com/docker/compose/blob/v2.40.3/pkg/compose/down.go). Fontes de implementação, sem atribuir essa versão ao cliente local não inventariado.
