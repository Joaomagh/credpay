# CredPay — relatório do projeto e do backlog

Relatório atualizado em 4 de novembro de 2026 com base nas evidências registradas no repositório. Projeto de portfólio de **João Pedro Magalhães**, responsável pela direção, pelas decisões de escopo e arquitetura e pelos aceites locais. [Perfil no GitHub](https://github.com/Joaomagh)

## O que é o CredPay

É um simulador de processamento assíncrono de transações. Você abre o console Angular, informa um valor fictício, recebe um identificador e acompanha o resultado. Nos exemplos abaixo, a regra está configurada com um limite de R$ 100. Um pedido de R$ 50 é aprovado e um de R$ 150 é rejeitado. O limite é aplicado a cada transação e permanece igual após a aprovação. O projeto usa dados fictícios e simula a decisão por valor.

### Da tela até o resultado

1. O Angular chama `POST /transacoes` com o valor, a moeda e uma chave de idempotência.
2. O serviço de transações grava o pedido como `PENDENTE` e a intenção de emitir `TransacaoCriada` no mesmo commit do PostgreSQL. A API retorna `201 Created` e o UUID.
3. O publicador entrega o evento ao RabbitMQ. O serviço de processamento consome a mensagem, aplica a regra e grava a decisão junto com a intenção de publicar `TransacaoProcessada`.
4. O resultado volta pelo RabbitMQ. O primeiro serviço atualiza o estado e registra o histórico. A tela consulta `GET /transacoes/{id}` para mostrar a informação atual.

O POST pode responder antes da aprovação porque seu compromisso é registrar o pedido. A decisão acontece em segundo plano. Se o navegador perder a resposta e reenviar o mesmo pedido com a mesma chave, recebe o recibo original. O GET pelo UUID informa o estado mais recente, inclusive depois de o pedido sair de `PENDENTE`.

## Onde está o trabalho de engenharia

| Problema | Decisão aplicada | Evidência |
| --- | --- | --- |
| O banco confirma a criação e a publicação falha | Os dados e a intenção de publicar ficam no mesmo commit, via outbox transacional em cada serviço | Retorno sem rota preservou a intenção original. Após restaurar o vínculo no RabbitMQ, o mesmo evento foi recuperado |
| O cliente repete o POST ou o broker reentrega o evento | Chave de idempotência no HTTP, identidade dos eventos e constraints/locks nas gravações | Replay preservou o recibo original. Duplicatas equivalentes preservaram decisão e histórico |
| O processador para com um pedido em andamento | Filas duráveis, ack após commit e tentativas limitadas | A transação ficou pendente e concluiu com o mesmo UUID quando o processador voltou |
| É preciso explicar por que um pedido ficou pendente | Métricas de confirmação/retorno, cruzadas com estado, outbox e filas | No teste local, o contador de retornos subiu três vezes em cada publicador durante seu cenário de falha. O delta conta tentativas. A unicidade foi verificada separadamente nos registros |
| Uma alteração pode quebrar contrato ou operação | Testes de domínio, HTTP e integração com infraestrutura real, mais cenários do navegador e verificações de CI | Os cortes, os cenários e seus limites estão nas evidências da v1 |

Os serviços têm responsabilidade e bancos separados. Isso permite exercitar fronteiras e falhas de comunicação, com o custo de operar dois aplicativos, dois bancos e um broker. A outbox preserva a intenção de publicar, e a idempotência protege a aplicação quando a entrega ocorre mais de uma vez. [Validação da v1](V1_VALIDATION.md) · [Diagnóstico por métricas](../infra/observability/README.md)

## Entregas verificadas

- **Backend.** Criação, aprovação/rejeição, consulta por UUID, replay, concorrência, rollback, reentrega e recuperação nos cenários registrados. O esgotamento de tentativas pode manter o pedido pendente para recuperação. A aplicação de `FALHOU` tem prova em teste controlado. O início operacional geral está fora da API. [Confirmação de falha](CONFIRMACAO_FALHA.md)
- **Frontend.** Criação e consulta responsivas com a API real. O console recupera o recibo após recarregar a aba e acompanha a evolução da mesma transação. O CI cobre seis cenários no Chromium em desktop e celular. [Console Angular](../frontend/README.md)
- **Infraestrutura local.** Compose, Kubernetes com kind e diagnóstico causal por métricas passaram nos ensaios assistidos. Os testes confirmaram persistência, recuperação e remoção dos recursos descartáveis próprios. [Validação da v1](V1_VALIDATION.md) · [Kubernetes local](../infra/k8s/LOCAL.md)
- **Candidato.** O corte operacional `e07f0aa` está identificado e condicionado aos aceites finais. As provas locais e os inventários mantêm a identidade de cada corte. [Candidato da v1](V1_CANDIDATE.md)

## O que falta

| Ordem | Item | Como saber que terminou |
| --- | --- | --- |
| 1 | **B08.5b · Aceite pessoal, adiado a pedido de João** | João executa o roteiro com autonomia e explica, em suas palavras, por que o POST volta pendente, onde entra a fila e como a transação se recupera com o mesmo UUID. [Roteiro](DEMO.md) |
| 2 | **B08 · Fechamento da v1, condicionado ao aceite pessoal** | Conferir o SHA, os checks aplicáveis, a triagem e as evidências. Registrar a decisão de fechamento após os aceites. Se o código ou a baseline mudar, revalidar os cenários afetados. [Candidato da v1](V1_CANDIDATE.md) |
| Em paralelo | **Manutenção de segurança** | Avaliar uma imagem oficial com as correções de curl e zlib registradas na triagem. Confirmar os pacotes instalados e repetir os testes e o inventário de cada alvo afetado antes de promover a imagem. Acompanhar o achado de GnuTLS registrado sem correção naquele corte. [Triagem](../infra/security/TRIAGE.md) |
| Etapa de portfólio | **Link público gratuito para recrutadores** | Definir e autorizar o destino. Provar que o Angular e a pilha Java/PostgreSQL/RabbitMQ funcionam dentro do orçamento zero, inclusive no primeiro acesso após ociosidade. Validar HTTPS e jornada em desktop e celular, com dados fictícios e recursos administrativos internos. [Plano da demo pública](PUBLIC_DEMO_PLAN.md) |
| Depois da v1 | **Painel e pesquisa de isolamento** | Prometheus/Grafana e a pesquisa de sandbox seguem adiados. Retomadas exigem seus próprios testes e análise de segurança. [Backlog](BACKLOG.md) |

A revisão histórica foi aceita no recorte acessível. Um log e cinco artefatos continuam marcados como **não verificados** e voltam à análise se forem recuperados. [Limites da validação](V1_VALIDATION.md)

Na revisão documental, a fila vigente não apresenta correção funcional aberta no fluxo principal ou no console. O estado da v1 é **candidato operacional com aceites técnicos locais assistidos concluídos**. A prática autônoma e a decisão final seguem pendentes. O link público é uma etapa separada de apresentação. [Backlog detalhado](BACKLOG.md)
