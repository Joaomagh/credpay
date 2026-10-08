# Entenda o CredPay

CredPay é um simulador de backend para processamento assíncrono de transações. O usuário envia uma transação fictícia, o sistema a registra, outro serviço aplica uma regra de limite e o resultado fica disponível para consulta. Não movimenta dinheiro real; aprovação aqui é o resultado da regra didática, sem análise de crédito, saldo ou liquidação financeira.

## Um exemplo completo

A interface atual é uma API HTTP, usada por programas ou ferramentas de requisição; não há frontend na v1. O limite é configurado por moeda e vale para cada transação: não representa saldo de uma conta e não diminui depois de uma aprovação.

Com BRL e um limite configurado de R$ 100 por transação:

1. Um cliente envia uma transação de R$ 50 e uma chave de idempotência.
2. O serviço de transações valida os dados, grava PENDENTE e responde que o pedido foi registrado.
3. A intenção de processamento segue pelo RabbitMQ.
4. O processador aplica o limite e grava APROVADA.
5. Um evento de resultado retorna ao serviço de transações, que atualiza estado e histórico.
6. A consulta passa a mostrar APROVADA. Uma transação de R$ 150 segue o mesmo percurso, com REJEITADA.

A resposta inicial não precisa esperar o processamento. Existe um intervalo em que a consulta mostra PENDENTE: é a consistência eventual. Repetir o mesmo pedido com a mesma chave mantém a resposta original; consultar o identificador mostra o estado atual.

## O problema técnico que resolvemos

O desafio é manter banco de dados e mensagens coerentes diante de falhas. Uma conexão pode cair, um serviço pode parar e uma mensagem pode chegar mais de uma vez. Precisamos preservar a transação registrada, evitar decisões/históricos duplicados e permitir recuperação nos cenários documentados.

| Situação | Solução aplicada | Por que isso importa |
|---|---|---|
| Pedido HTTP repetido por timeout | Chave de idempotência; mesmo pedido repete a resposta, conteúdo diferente gera conflito | A repetição não cria outra transação |
| Banco grava, mas RabbitMQ está indisponível | Transactional Outbox: dados e intenção de evento ficam no mesmo commit local | A intenção permanece durável para uma publicação posterior |
| Mensagem chega novamente | Consumidor confere identidade/conteúdo e reaproveita resultado equivalente | Reentrega não duplica decisão ou histórico; conflito não sobrescreve dados |
| Duas tentativas concorrentes | Transações, constraints e locks nas identidades relevantes | A consistência não depende apenas de uma consulta feita antes da gravação |
| Processador indisponível | Mensagem durável e transação PENDENTE; recuperação após reinício comprovada | Indisponibilidade técnica não inventa uma rejeição financeira |
| Falha temporária ou mensagem problemática | Retry limitado e fila de mensagens com falha, a DLQ | Tentativas têm limite e o problema pode ser investigado/recuperado |
| Confirmação de consumo antes de salvar | Listener conclui depois do commit; ack após dados duráveis | Reentrega pode ser tratada sem perder o resultado já persistido |

Outbox não elimina duplicatas: uma queda entre publicar e marcar a intenção pode repetir o evento. Por isso a entrega é pelo menos uma vez e os consumidores são idempotentes. Banco e broker não participam de uma transação distribuída única.

## Tecnologias e motivo de cada escolha

| Aplicado | Papel no projeto |
|---|---|
| Java 21 / Spring Boot 3 / Maven | Implementar, configurar e empacotar os dois aplicativos backend |
| Dois serviços, com dados próprios | Separar entrada/consulta da decisão; praticar fronteiras e comunicação distribuída |
| PostgreSQL/JPA/JDBC/Flyway | Persistir dados reais, proteger invariantes e evoluir schema por migrations versionadas |
| BigDecimal e java.time | Representar dinheiro e instantes com regras explícitas de precisão |
| RabbitMQ/Spring AMQP | Transportar eventos e estudar confirmação, reentrega, retry e DLQ |
| Separação domínio/aplicação/infraestrutura | Manter regras de negócio distinguíveis de HTTP, banco e broker |
| TDD/JUnit/AssertJ/Testcontainers | Construir comportamentos com testes e verificar banco/broker reais nas integrações |
| GitHub Actions/Checkstyle | Executar verificações repetíveis e impedir violações de estilo estabelecidas |
| Gitleaks e inventário Trivy | Detectar padrões de segredo e vulnerabilidades; registrar achados e priorizar correções |
| Docker/Compose | Executar os aplicativos com suas dependências e provar preservação de dados após down/up |

A escolha de dois serviços aumenta a complexidade, mas atende ao objetivo de estudar sistemas distribuídos. Para uma aplicação pequena sem esse objetivo, um único aplicativo poderia reduzir custos de desenvolvimento e operação.

## Por que fizemos este projeto

O objetivo aprovado é aprendizado demonstrável e portfólio Java backend. O domínio de transações foi escolhido porque obriga decisões sobre valores, duplicação, concorrência, persistência e falhas. O resultado esperado é conseguir executar o sistema, explicar as escolhas, demonstrar testes e discutir limites em uma entrevista.

Não existe validação de mercado ou cliente real registrada. O problema resolvido até agora é de engenharia: fluxo assíncrono consistente nos cenários testados. Métricas de negócio como receita ou redução de fraude não foram medidas.

## A regra FALHOU escolhida em 2026-10-08

João aprovou a direção recomendada: esgotar as tentativas não torna a transação automaticamente FALHOU. Ela continua PENDENTE e pode ser recuperada. FALHOU representa uma falha técnica confirmada como irrecuperável, e é terminal.

Essa decisão de produto não implementa sozinha a transição. Ainda precisamos definir o mecanismo de confirmação, condições/evidências e testes. Aprovação/rejeição pelo limite e falha técnica são conceitos diferentes.

## O que já podemos demonstrar e o que falta

O CI já demonstrou criação/consulta, aprovação/rejeição, duplicatas/replay, concorrência, rollback, reentrega e recuperação nos casos registrados. Imagens demonstraram o fluxo; Compose também comprovou preservação após down/up. Isso não cobre toda janela de crash possível nem comprova execução no computador de João enquanto o engine Docker local estiver indisponível.

Faltam tratamento dos demais achados de segurança, observabilidade útil com métricas/painel/diagnóstico, Kubernetes local executado, isolamento verificável do ambiente de desenvolvimento, mecanismo FALHOU e ensaio da apresentação. A estimativa de escopo permanece aproximadamente 65% concluído / 35% restante; não é percentual calculado por quantidade de PRs/testes ou previsão de prazo.

## Etapas e estimativa de conclusão

| Etapa | Concluído | Restante | O que falta para encerrar |
|---|---:|---:|---|
| Governança e isolamento do ambiente | 30% | 70% | Executar sandbox e comprovar restrições de acesso, privilégios e rede |
| Fundação reproduzível | 95% | 5% | Confirmar o quickstart no computador de João com Docker Linux disponível |
| Primeiro comportamento com TDD | 100% | 0% | Aceite atendido; manter a disciplina nos próximos comportamentos |
| Fluxo assíncrono confiável | 95% | 5% | Refinar e testar a confirmação de falha irrecuperável |
| Qualidade e resiliência | 75% | 25% | Tratar dívida relevante e consolidar cenários de falha, recuperação e limites |
| Kubernetes e observabilidade | 5% | 95% | Executar cluster local, provar fluxo/persistência e criar métricas/painel úteis |
| Entrega e portfólio | 65% | 35% | Tratar achados de dependências/imagens, revisar publicação e ensaiar a demo |

Os percentuais usam pesos de escopo diferentes, registrados no backlog. Não devem ser somados ou usados como prazo. A maior lacuna está na operação e no diagnóstico; o fluxo principal já foi demonstrado. O projeto só encerra quando os critérios do plano forem cumpridos, inclusive João conseguir explicar e executar o trabalho.

Para executar a demonstração e localizar as evidências, consultar [o roteiro](DEMO.md), [o backlog](BACKLOG.md) e [a especificação](../spec.md).
