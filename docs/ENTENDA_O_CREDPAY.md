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
- **Frontend.** Criação e consulta responsivas, login, logout e recuperação do recibo por conta. O CI cobre seis casos no modo padrão e dois no modo com contas no Chromium em desktop e celular. O teste com duas contas prova aprovação real, saída em outra aba, 404 para consulta alheia e retomada do recibo sem outro POST. [Console Angular](../frontend/README.md) · [Compose CI](https://github.com/Joaomagh/credpay/actions/runs/41907646479)
- **Segurança da demo.** Sessão no servidor, CSRF, autorização pelo dono e idempotência por conta. A criação limita corpo e expansão decimal. O perfil local limita autenticação por origem e operações financeiras por conta, com 429 e `Retry-After`. A revisão passou em 542 testes de Transações e 45 de Angular. [Transações](https://github.com/Joaomagh/credpay/actions/runs/41907645754) · [Angular](https://github.com/Joaomagh/credpay/actions/runs/41907645015)
- **Infraestrutura local.** Compose, Kubernetes com kind e diagnóstico causal por métricas passaram nos ensaios assistidos. Os testes confirmaram persistência, recuperação e remoção dos recursos descartáveis próprios. [Validação da v1](V1_VALIDATION.md) · [Kubernetes local](../infra/k8s/LOCAL.md)
- **Candidato.** O corte `51f8476` está identificado e condicionado ao aceite pessoal e à decisão de release. Os resultados da demo com contas pertencem às revisões posteriores registradas nas suas PRs. [Candidato da v1](V1_CANDIDATE.md)

## O que falta

| Ordem | Item | Como saber que terminou |
| --- | --- | --- |
| 1 | **B08.5b · Aceite pessoal, adiado a pedido de João** | João executa o roteiro com autonomia e explica, em suas palavras, por que o POST volta pendente, onde entra a fila e como a transação se recupera com o mesmo UUID. [Roteiro](DEMO.md) |
| 2 | **B08 · Fechamento da v1, condicionado ao aceite pessoal** | Conferir o SHA, os checks aplicáveis, a triagem e as evidências. Registrar a decisão de fechamento após os aceites. Se o código ou a baseline mudar, revalidar os cenários afetados. [Candidato da v1](V1_CANDIDATE.md) |
| Em paralelo | **Manutenção de segurança** | A imagem oficial com correções de curl e zlib passou no CI do candidato. Acompanhar os achados GnuTLS e repetir inventário e testes quando a baseline mudar. [Triagem](../infra/security/TRIAGE.md) |
| Etapa de portfólio | **Link público gratuito para recrutadores** | Definir e autorizar o destino. Validar orçamento zero, HTTPS e cookie Secure, provisionamento e expiração de contas fictícias, proxy, cotas e jornada em desktop e celular. [Plano da demo pública](PUBLIC_DEMO_PLAN.md) |
| Evolução de qualidade | **SonarQube e Jenkins** | SonarQube está em avaliação para análise estática e cobertura, com baseline própria. Jenkins permanece como opção de laboratório para operação de CI. O GitHub Actions é o pipeline vigente. [Backlog](BACKLOG.md) |
| Depois da v1 | **Painel e pesquisa de isolamento** | Prometheus/Grafana e a pesquisa de sandbox seguem adiados. Retomadas exigem seus próprios testes e análise de segurança. [Backlog](BACKLOG.md) |

A revisão histórica foi aceita no recorte acessível. Um log e cinco artefatos continuam marcados como **não verificados** e voltam à análise se forem recuperados. [Limites da validação](V1_VALIDATION.md)

A v1 tem um candidato identificado e evidências técnicas assistidas. A decisão de release aguarda o ensaio autônomo no corte escolhido. A demo pública tem critérios de segurança e operação próprios. [Backlog detalhado](BACKLOG.md)
