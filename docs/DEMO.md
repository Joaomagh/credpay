# Demonstração e explicação do CredPay

O roteiro usa somente transações fictícias e a v1 existente. Compose/fluxo estão comprovados no CI; executar no computador de João ainda exige engine Docker Linux acessível. Kubernetes, painel de observabilidade e sandbox do agente continuam pendentes. CI verde não comprova que João consegue explicar o projeto: a apresentação precisa ser ensaiada por ele.

## Preparar a demonstração

1. Escolher um checkout revisado e conferir os checks do seu SHA. Os workflows de módulos, Flow, Images, Compose e Secret Scan têm responsabilidades diferentes; package com skipTests só prepara artefatos.
2. Conferir Java 21, PowerShell 7.3+, Compose 2 com up --wait e engine Docker Linux acessível. Se infraestrutura falhar, registrar o impedimento; não chamar isso de falha de negócio nem substituir execução por screenshot antigo.
3. Seguir o [quickstart Compose](../infra/compose/README.md): exemplo público fictício, JARs e imagens do mesmo SHA, Prepare antes de Activate. Não sobrescrever configuração local existente, trocar credenciais de volumes persistidos ou apagar dados para contornar erro.
4. Executar, na raiz e em PowerShell, depois do preparo/build descritos no quickstart:

```powershell
./infra/compose/demo.ps1 -Action Prepare
./infra/compose/demo.ps1 -Action Activate
./infra/compose/demo.ps1 -Action Demo
```

O roteiro deve conferir políticas/filas e consumidores antes do fluxo. Demo cria 50.000 e 150.000 BRL com limite 100.00: POST retorna PENDENTE e GET converge para APROVADA/REJEITADA; replay preserva resposta e Location originais. Ele imprime conclusões sem UUIDs, payloads ou credenciais. Usar as evidências do teste Images para explicar snapshots completos de cinco tabelas; Demo não prova sozinho toda a matriz de idempotência/concorrência.

5. Encerrar com `./infra/compose/demo.ps1 -Action Down`, preservando volumes. Para retomar: Prepare/Activate com mesmo projeto e configuração compatível. Smoke usa exclusivamente projeto descartável, recusa volumes prévios e comprova os mesmos registros após down/up; não usar o nome de um projeto com dados para essa prova.

## Demonstrar recuperação

O [runbook dos JARs](../infra/e2e/README.md) explica os quatro casos de FluxoCredPayE2E: estados finais, duplicatas/replay, parada controlada/reinício do processador e DLQ recuperada após corrigir limite ausente. Preparar ambos JARs e executar o teste dedicado exige Docker Linux. Não importar código do processador no teste do serviço de transações nem inserir resultado financeiro por SQL para encenar uma recuperação.

As evidências estão em spec §9.53–9.54 e nos workflows. Parada controlada comprova indisponibilidade e reinício, sem provar crash abrupto em toda janela. Retry limitado tem prova nos listeners; DLQ não é consumida automaticamente. Replay manual desse teste é exclusivo do broker descartável, não ferramenta operacional autorizada para ambiente externo. Sem regra FALHOU definida, esgotar uma falha operacional conserva PENDENTE recuperável.

## Explicação de aproximadamente três minutos

“CredPay simula uma transação assíncrona com dois serviços Java, cada um dono do seu PostgreSQL. A API cria uma transação PENDENTE. O processador decide APROVADA ou REJEITADA pelo limite configurado; o resultado volta por RabbitMQ e atualiza o estado consultável com histórico.

O problema central foi consistência entre banco e mensageria. A criação grava transação e intenção de evento no mesmo commit local. O processador também grava decisão e sua outbox atomicamente. Os publicadores marcam a intenção somente após confirmação do broker e ausência de retorno por falta de rota. Uma queda entre publicar e marcar ainda pode causar duplicata: a entrega é pelo menos uma vez.

Por isso, idempotência existe em duas fronteiras. No HTTP, a mesma chave e pedido equivalente repetem a resposta original PENDENTE; GET mostra o estado atual. Nos consumidores, identidade e conteúdo são conferidos. Replay equivalente preserva o resultado, e conteúdo divergente não sobrescreve dados. Constraints e locks transacionais protegem chamadas concorrentes.

Valores usam BigDecimal. Equivalência monetária compara valor decimal, enquanto o snapshot conserva os dados originais. Instantes usam java.time; contratos que exigem nanos são persistidos com segundos e nanos, e o instante de aplicação do histórico é normalizado para micros do PostgreSQL. Precisão e equivalência têm regras explícitas.

Os listeners retornam depois do commit, permitindo ack após dados duráveis. Rejeições permanentes seguem para DLQ e falhas operacionais recebem retry limitado a três tentativas. Esgotar retry não inventa uma decisão financeira: a transação pode continuar PENDENTE. FALHOU ainda precisa de regra de produto.

O CI prova fluxo completo, duplicatas, rollback, reentrega e recuperação nos cenários documentados. Imagens comprovam estados finais e replay; Compose também comprova preservação após down/up. Essas provas não cobrem todo crash possível.

Uso uma réplica publicadora por serviço e broker de nó único; não alego alta disponibilidade. Kubernetes está proposto sem execução, observabilidade ainda precisa de painel e diagnóstico, e apps non-root não equivalem ao sandbox de desenvolvimento. O fechamento da v1 exige demonstrar o sistema e defender essas decisões com autonomia.”

## Perguntas para João responder com evidências

| Pergunta | Ponto que precisa explicar | Código/evidência |
|---|---|---|
| Por que usar outbox e ainda aceitar duplicatas? | Atomicidade local; janela confirmação/marcação; identidade estável no reenvio | [CriarTransacaoService](../transacoes-service/src/main/java/br/com/credpay/transacoes/application/CriarTransacaoService.java), [PublicarOutboxService](../transacoes-service/src/main/java/br/com/credpay/transacoes/application/PublicarOutboxService.java); spec §9.53–9.54 |
| Por que POST replay retorna PENDENTE e GET retorna APROVADA? | Resposta original da operação versus estado atual do recurso; chave equivalente/conflito | spec §9.45, §9.52 e §9.62; Flow e Images preservam resposta/Location e registros |
| Como impedir duas decisões concorrentes? | Locks por identidades, constraints, transação e replay/conflito explícitos | [RegistrarProcessamentoService](../processamento-service/src/main/java/br/com/credpay/processamento/application/RegistrarProcessamentoService.java), [AplicarResultadoService](../transacoes-service/src/main/java/br/com/credpay/transacoes/application/AplicarResultadoService.java) |
| O que ocorre entre commit e ack? | Dados já duráveis; reentrega exige consumidor idempotente; janela testada não é exactly-once | spec §9.37 e §9.48 e runbook Flow; testes de listeners |
| Por que DLQ não significa FALHOU? | Falha operacional e decisão financeira são distintas; política final ainda não aprovada | spec §9.54; recuperação com limite corrigido e replay manual |
| Quais limites existem hoje? | Publicador de réplica única, broker sem HA, engine local ausente, observabilidade/sandbox pendentes | README, plano §6 e backlog |

## Critério de encerramento da apresentação

João deve executar o quickstart, explicar as seis respostas apontando código/testes e identificar os limites atuais. Registrar dúvidas concretas para o próximo incremento. Não marcar aprendizado, Kubernetes, sandbox ou v1 como concluídos apenas porque este roteiro foi escrito. Análise de dependências/imagens e revisão de publicação continuam gates próprios.
