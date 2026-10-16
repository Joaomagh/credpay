# Fluxo dos aplicativos reais

O workflow `CredPay Application Flow CI` compila os dois JARs do mesmo checkout e executa `FluxoCredPayE2E` e `EncerramentoCredPayE2E` no módulo de transações. Cada cenário inicia processos Java independentes, dois PostgreSQL próprios e um RabbitMQ descartáveis, nas versões/digests já aprovadas. O coordenador não importa código Java do processador nem insere resultados por SQL; lê evidências JDBC e envia HTTP ao primeiro aplicativo. As fixtures de encerramento abaixo acrescentam comandos locais de teste.

As suítes completas dos módulos continuam em seus workflows. O nome `FluxoCredPayE2E` fica fora da descoberta padrão do Surefire porque exige ambos JARs; o workflow dedicado o seleciona explicitamente. `-DskipTests package` só prepara os artefatos naquele job, sem substituir as verificações dos módulos. Não há skip automático por ausência de Docker/JAR: isso é falha de ambiente.

## Preparação e aceite

1. Iniciar ambos com consumo/publicação/topologias desligados, health Rabbit explicitamente habilitado; consultar health e conferir as duas exchanges produtoras reais no broker.
2. Encerrar processos, reiniciar com topologias ligadas e consumo/publicação desligados. Importar os dois artefatos de políticas e conferir filas quorum/argumentos/políticas efetivas/flag/exchanges/bindings e nenhum consumidor.
3. Encerrar processos e reiniciar com consumidores/publicadores habilitados. Aguardar consumidores exatos, ack e prefetch; conferir bancos separados sem tabela do outro agregado.
4. POST de 50.000 BRL e 150.000 BRL com limite real de 100.00 BRL deve devolver PENDENTE e alcançar GET APROVADA/REJEITADA. Conferir registro do processador, ambas outboxes publicadas, causa/correlação/histórico único, instante do resultado e dados/escala preservados; quatro filas vazias.
5. Republicar entrada e saída reais das outboxes, preservando corpo/identidade/propriedades. Confirmar mandatory sem returns e observar avanço de ack por fila depois que a baseline de originais/duplicatas anteriores foi coletada. Snapshot completo das cinco tabelas permanece intacto; repetir POST com mesma chave/escala equivalente preserva corpo/Location original PENDENTE e GET final.
6. Parar somente o processador e aguardar seu consumidor desaparecer, mantendo consumidor de resultado ativo. Criar outra transação: POST/GET PENDENTE, entrada publicada/pronta sem unacked, processamento/outbox de saída/histórico ausentes. Reiniciar o JAR no mesmo banco/broker e exigir APROVADA com causa original, cinco registros únicos, dados/outbox original preservados e filas vazias. Comprovado no [Flow CI #7](https://github.com/Joaomagh/credpay/actions/runs/37161171631), com os três cenários reais verdes.

Os cenários de replay precedem o reinício para preservar suas baselines de contadores durante a sessão AMQP original; o cenário de recuperação também pode ser selecionado isoladamente. Parada controlada de um processo comprova indisponibilidade e reinício, sem simular crash abrupto, queda de broker ou HA.

7. USD válida sem limite configurado deve ir à DLQ, continuar PENDENTE e não criar decisão/saída/histórico. Um replay mandatory sem rota conserva mensagem e snapshots completos. Reiniciar somente processador com limite USD corrigido não consome DLQ automaticamente; republicar o corpo/propriedades originais e exigir APROVADA causal/única. Comprovado no [Flow CI #10](https://github.com/Joaomagh/credpay/actions/runs/37161905170), quatro cenários reais verdes.

Replay neste experimento usa cliente AMQP já transitivo, `basicGet(false)`, publisher confirms/returns e `basicAck` somente depois de confirmação sem retorno; fechamento de canal/conexão sem ack permite reentrega na falha. Uma queda entre confirmação e ack pode duplicar entrega; identidade original/idempotência continuam necessárias. Não é ferramenta operacional geral nem autorização para remover mensagens de ambiente externo. A política de três tentativas é comprovada nos testes do listener; aqui são observados DLQ e diagnóstico fixo de esgotamento, sem contar cada invocação dentro do JAR.

Health sozinho não prova declaração AMQP; a conferência do broker é obrigatória. As portas são alocadas dinamicamente e os prazos são limitados; falha de bind/startup/health impede o aceite. Cleanup encerra somente processos filhos criados pelo teste, força término se necessário e preserva referências de sobreviventes para nova tentativa de cleanup.

Logs dos subprocessos ficam em `.local/e2e/<UUID>/`, ignorados pelo Git. Não imprimir/publicar arquivos integrais: inspecionar somente diagnóstico seguro da falha. O workflow não publica logs como artefatos. API management lê contadores agregados apenas no broker isolado, sem outros produtores; não identifica individualmente entregas nem promete ausência geral de duplicatas. Ambiente é descartável, sem deployment externo, segredo real, HA ou benchmark. Recuperação operacional continua em B06.

## Executar

Java 21 e Docker Linux acessível são necessários. Em cada módulo, preparar o JAR com Maven Wrapper:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress -DskipTests package
```

Depois, em `transacoes-service`:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress '-Dtest=FluxoCredPayE2E,EncerramentoCredPayE2E' test
```

Linux usa `./mvnw`. O teste resolve os dois JARs em seus diretórios `target` dentro do workspace. O [CI vertical #4](https://github.com/Joaomagh/credpay/actions/runs/37160110175) passou os dois estados finais, duplicatas e replay pelos aplicativos reais, com preparação conferida e snapshots preservados. Compilação local não substitui execução com containers.

## Diagnóstico de retornos sem rota

Os JARs da fixture usam o perfil diagnostics. Antes dos cenários de reinício, dois casos adicionais retiram um binding do broker descartável e consultam outcome:returned por HTTP no produtor correspondente. Exigem transação PENDENTE, intenção ainda não publicada e snapshots íntegros; restauram o binding em finally e verificam recuperação causal do mesmo evento e registros únicos. O total passa de quatro para seis casos; os seis casos passaram no Flow CI84/run38019765157, sem falhas/erros/skips. O perfil padrão dos aplicativos permanece inalterado.

## Encerramento controlado entre JARs reais

`EncerramentoCredPayE2E` mantém os seis casos financeiros acima e acrescenta seis próprios: confirmação positiva/replay; queda entre commit e ACK em cada consumidor; criação atrasada bloqueada; decisão financeira anterior APROVADA/REJEITADA preservada. Evidências red/green e estado de aceite em spec9.96. As suítes completas dos módulos continuam obrigatórias.

As mains de teste não têm estereótipo Spring nem entram nos JARs publicados. O coordenador copia somente a classe permitida para um diretório exclusivo e usa PropertiesLauncher para executar a aplicação original com os casos controlados registrados antes do refresh. Cada processo recebe apenas seu banco e exige proxies transacionais; nenhum endpoint administrativo é criado.

Comandos stdin limitados usam UUIDs canônicos e respostas fixas. As mensagens vêm das outboxes reais, com propriedades AMQP originais, confirmação positiva sem retorno antes da marcação e parsers reais antes dos casos de uso. As quatro filas quorum `credpay.fixture.*` são próprias; listeners/publicadores operacionais ficam desligados. Não se insere decisão financeira por SQL nem se interpreta evidenceRef como autorização.

Na janela de queda, o teste consulta o commit por conexão independente e exige ready0/unacked1 antes de encerrar somente seu processo filho. Depois exige ready1/unacked0, reinicia no mesmo banco/broker e compara snapshots completos após a reentrega. A resposta negativa pode chegar antes do resultado financeiro: mantém PENDENTE até a saída original concluir APROVADA/REJEITADA. Criação posterior à confirmação deve gerar apenas recibo bloqueado.

Isso é uma prova descartável de integração. Não ativa encerramento operacional, replay administrativo, autorização de operador, deploy externo, HA ou sandbox do agente. Logs dos filhos continuam ignorados em `.local/e2e/closure-*`, sem upload; falhas imprimem diagnóstico fixo, sem corpos/snapshots.
