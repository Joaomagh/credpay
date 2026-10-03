# Fluxo dos aplicativos reais

O workflow `CredPay Application Flow CI` compila os dois JARs do mesmo checkout e executa `FluxoCredPayE2E` no módulo de transações. O teste inicia dois processos Java independentes, dois PostgreSQL próprios e um RabbitMQ descartáveis, nas versões/digests já aprovadas. Não importa código Java do processador nem insere resultados por SQL; só lê evidências JDBC e envia HTTP ao primeiro aplicativo.

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
.\mvnw.cmd --batch-mode --no-transfer-progress -Dtest=FluxoCredPayE2E test
```

Linux usa `./mvnw`. O teste resolve os dois JARs em seus diretórios `target` dentro do workspace. O [CI vertical #4](https://github.com/Joaomagh/credpay/actions/runs/37160110175) passou os dois estados finais, duplicatas e replay pelos aplicativos reais, com preparação conferida e snapshots preservados. Compilação local não substitui execução com containers.
