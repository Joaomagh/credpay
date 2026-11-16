# CredPay — relatório do projeto e do backlog

Relatório revisado em 16 de novembro de 2026 com base nas evidências registradas no repositório. Projeto de portfólio de **João Pedro Magalhães**, responsável pela direção, pelas decisões de escopo e arquitetura e pelos aceites locais. [Perfil no GitHub](https://github.com/Joaomagh)

## O que é o CredPay

É um simulador de processamento assíncrono de transações. Você abre o console Angular, informa um valor fictício, recebe um identificador e acompanha o resultado. Nos exemplos abaixo, a regra está configurada com um limite de R$ 100. Um pedido de R$ 50 é aprovado e um de R$ 150 é rejeitado. O projeto usa dados fictícios e simula a decisão por valor; não movimenta dinheiro.

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

### Segurança e qualidade no corte mais recente

O cliente AMQP foi atualizado na [PR #237](https://github.com/Joaomagh/credpay/pull/237); as três ocorrências MEDIUM anteriores desapareceram dos quatro alvos Java analisados. A [PR #238](https://github.com/Joaomagh/credpay/pull/238) passou a inventariar também PostgreSQL e RabbitMQ. A [PR #239](https://github.com/Joaomagh/credpay/pull/239) substituiu a base dos aplicativos por UBI10: o inventário de 07/11 registrou zero achados nos JARs e imagens de ambos os serviços em AMD64 e ARM64, e confirmou a ausência de APT. Isso é um resultado de inventário daquele corte, não uma garantia universal. [Triagem completa](../infra/security/TRIAGE.md)

O banco ainda bloqueia a publicação: a imagem PostgreSQL 17.11/Bookworm registrou **4 ocorrências CRITICAL e 75 HIGH**. A candidata Alpine foi apenas avaliada e também registrou **1 CRITICAL e 25 HIGH**. Não houve aceitação desses achados, troca de imagem ativa ou migração de volumes. O RabbitMQ registrou zero CRITICAL/HIGH e um MEDIUM no mesmo inventário. Evidência e limites: `spec.md` §9.169.

A [PR #240](https://github.com/Joaomagh/credpay/pull/240) prepara o perfil público: HTTPS, cookie seguro, origem identificada entre o proxy e a API e prazo absoluto de sessão. Os testes locais de política/configuração passaram (15/15); a integração final e o gate de qualidade ainda precisam ser conferidos no SHA final. Os testes Node do proxy não equivalem a executá-lo no runtime hospedado do Cloudflare Workers. Não existe URL pública neste corte.

## O que falta

| Ordem | Item | Como saber que terminou |
| --- | --- | --- |
| 1 | **B08.5b · Aceite pessoal, adiado a pedido de João** | João executa o roteiro com autonomia e explica, em suas palavras, por que o POST volta pendente, onde entra a fila e como a transação se recupera com o mesmo UUID. [Roteiro](DEMO.md) |
| 2 | **B08 · Fechamento da v1, condicionado ao aceite pessoal** | Conferir o SHA, os checks aplicáveis, a triagem e as evidências. Registrar a decisão de fechamento após os aceites. Se o código ou a baseline mudar, revalidar os cenários afetados. [Candidato da v1](V1_CANDIDATE.md) |
| Bloqueador público | **Imagem do PostgreSQL** | Encontrar uma baseline corrigida, atribuir com precisão os achados restantes e repetir inventário, persistência e recuperação em AMD64/ARM64 antes de promover a imagem. [Triagem](../infra/security/TRIAGE.md) |
| Em andamento | **Perfil HTTPS e sessão pública** | Concluir a PR #240 com todos os checks e o gate verdes, confirmar a seleção do relógio e o perfil de autenticação no corte final. [Plano público](PUBLIC_DEMO_PLAN.md) |
| Etapa de portfólio | **Link público gratuito para recrutadores** | Definir e autorizar o destino. Validar orçamento zero, HTTPS e cookie Secure, provisionamento e expiração de contas fictícias, proxy, cotas e jornada em desktop e celular. [Plano da demo pública](PUBLIC_DEMO_PLAN.md) |
| Evolução de qualidade | **Proxy e runtime Workers** | Reproduzir as regras do proxy no runtime real do Workers em CI e comprovar HTTPS até a origem. SonarQube já integra o pipeline GitHub Actions; Jenkins continua opcional para laboratório. [Backlog](BACKLOG.md) |
| Depois da v1 | **Painel e pesquisa de isolamento** | Prometheus/Grafana e a pesquisa de sandbox seguem adiados. Retomadas exigem seus próprios testes e análise de segurança. [Backlog](BACKLOG.md) |

A revisão histórica foi aceita no recorte acessível. Um log e cinco artefatos continuam marcados como **não verificados** e voltam à análise se forem recuperados. [Limites da validação](V1_VALIDATION.md)

A v1 tem um candidato histórico identificado e evidências técnicas assistidas. As alterações posteriores exigem um novo corte final antes de promover a release. A decisão pessoal de João continua adiada. A demo pública tem critérios de segurança e operação próprios. [Backlog detalhado](BACKLOG.md)
