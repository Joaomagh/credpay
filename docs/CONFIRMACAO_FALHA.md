# Confirmação coordenada de encerramento técnico

Direção aprovada por João em 2026-10-10. Contrato em refinamento; ainda não implementado. Referências: spec.md §9.70 e §9.83.

## Objetivo e limite

Aplicar FALHOU somente depois de uma confirmação durável do processador que impeça uma decisão futura para a mesma transação. Retry esgotado, DLQ, timeout ou ausência de rota mantêm PENDENTE. A coordenação prova impedimento futuro; não transforma a causa original em irreversibilidade física demonstrada.

O início do pedido exige critério de falha técnica irrecuperável e evidência auditável ainda a definir. Até esse critério, não expor operação administrativa nem oferecer comando SQL. A aprovação da direção não aprova encerramento arbitrário de transações recuperáveis.

## Protocolo mínimo proposto

1. Transações persiste pedido identificado e intenção de envio no mesmo commit; permanece PENDENTE e preserva a resposta original do POST.
2. Processador serializa pedido e processamento normal pelo mesmo transactionId. Se uma decisão já existe, conserva decisão/outbox e não confirma encerramento; o resultado anterior prevalece.
3. Sem decisão, processador grava bloqueio durável de novas decisões e intenção de confirmação no mesmo commit. Replay equivalente conserva identidades; divergência é conflito sem sobrescrita.
4. Uma criação atrasada verifica o bloqueio sob o mesmo lock. Não pode produzir aprovação/rejeição; recebimento equivalente tem resultado durável explícito antes do ack, e identidade incompatível é conflito.
5. Transações aceita somente confirmação causal correspondente ao pedido/criação. Estado FALHOU, histórico e recebimento são atômicos e idempotentes. Pedido sem confirmação permanece PENDENTE; aprovação/rejeição já aplicada nunca é sobrescrita.

Pedido e confirmação terão eventos próprios versionados. TransacaoProcessada.v1 conserva seu contrato. Não compartilhar banco entre serviços nem interpretar silêncio como confirmação. Constraints de histórico e causalidade atuais precisam avaliação/migration própria antes de transportar nova confirmação.

## Critérios antes de implementação

- Fixar nomes, envelopes, motivos permitidos e identidade/equivalência/conflito de pedido/confirmação.
- Definir quem autoriza o pedido e qual evidência satisfaz irreversibilidade; não usar idade da transação ou DLQ isoladamente.
- Definir confirmação negativa quando já há decisão, resultado de criação atrasada e auditoria de resultado tardio incompatível.
- Provar que todas as entradas do processamento respeitam o mesmo bloqueio/lock.

Primeira fatia futura: regra de bloqueio no domínio/aplicação do processador, sem listener ou endpoint. TDD: bloqueio impede primeira decisão; decisão existente impede confirmação positiva; replay conserva identidades; conflito preserva vencedor. Depois PostgreSQL real cobre corrida pedido/processamento, atomicidade bloqueio/outbox e rollback. Só após essas provas entra transporte.

## Envelopes e arbitragem propostos — 2026-10-14

Dois eventos próprios, versão1; os nomes abaixo são proposta de contrato, ainda sem transporte. UUIDs não nulos e Instant preservado com nanos seguem a semântica atual. correlationId deve ser igual a transactionId.

| Campo | EncerramentoTransacaoSolicitado | EncerramentoTransacaoRespondido |
|---|---|---|
| eventId | identidade persistida do pedido | identidade persistida da resposta |
| causationId | eventId da criação original | eventId do pedido |
| occurredAt | instante original do pedido | instante persistido da resposta |
| data.transactionId | transação alvo | mesma transação |
| data.creationEventId | criação original, igual à causa | mesma criação original |
| data.motivo / evidenceRef | código permitido e referência opaca; definição pendente | não copiar texto livre de evidência |
| data.resultado | ausente | CONFIRMADO ou DECISAO_EXISTENTE |
| data.outputEventId | ausente | obrigatório somente em DECISAO_EXISTENTE; saída financeira já persistida |

CONFIRMADO corresponde ao bloqueio durável de novas decisões. DECISAO_EXISTENTE é resposta negativa ao encerramento: não aplica FALHOU nem cria outra decisão; se ainda PENDENTE, aguarda a saída original; se já APROVADA/REJEITADA, preserva o estado final. Criação incompatível é conflito; não pode receber confirmação válida.

Equivalência compara envelope semântico completo, incluindo instantes, identidades e dados; ordem JSON e representação textual equivalente de UUID/Instant não definem conflito. Replay devolve a resposta original antes de usar relógio, gerador ou criar outra intenção. Novo eventId para a mesma transação é outro pedido, não replay: deve ser rejeitado como conflito sem substituir o vencedor. Como a outbox marca publicação separadamente, reenviar preserva eventId/payload originais.

Processamento normal e pedido usam READ_COMMITTED e o mesmo lock por transactionId antes de ler decisão/bloqueio. Hashes efetivos de todas as identidades bloqueadas são deduplicados e ordenados, como no adapter atual, para evitar ordem inversa. A regra deve proteger a aplicação que persiste, antes de decisor.executar; o decisor puro isolado não garante concorrência ou durabilidade.

| Ordem dos commits | Resultado exigido |
|---|---|
| decisão financeira primeiro | snapshot/outbox preservados; resposta DECISAO_EXISTENTE |
| bloqueio primeiro | resposta CONFIRMADO e criação posterior sem decisão financeira |
| pedido equivalente novamente | mesma resposta/identidades; nenhuma escrita adicional |
| pedido divergente ou criação incompatível | conflito seguro e vencedor intacto |
| rollback antes de confirmação | nenhum bloqueio ou resposta parcial; nova tentativa permitida |

Criação atrasada equivalente exige recebimento bloqueado durável com sua semântica completa, para comparar duplicatas; não fabricar ProcessamentoRegistrado financeiro. Ack somente após commit, tanto do pedido quanto dessa criação. Toda entrada que grava decisão deve consultar o bloqueio, inclusive chamada direta ao caso de uso. Resultado financeiro incompatível posterior é conflito preservado para diagnóstico, sem sobrescrever terminal; política concreta de auditoria e DLQ ainda precisa ser definida, sem ciclo infinito ou descarte silencioso.

Este refinamento permite revisar identidades, vencedor e matriz TDD. Motivos/autoridade/evidência de início continuam impedindo ativação operacional; nenhum comando do operador prova sozinho irreversibilidade.
## Estado e conclusão

Contrato preliminar revisável. Regra pura e schema preparatório próprios integrados nas PRs #138 e #139; ainda não há pedido operacional, confirmação transportada ou estado FALHOU aplicado. B06.7 encerra observação de retornos; não conclui FALHOU, dashboard, Kubernetes ou sandbox. Percentual global permanece estimativa aproximada de 65%; critérios de saída governam a conclusão.

## Primeira fatia controlada — 2026-10-14

João aprovou começar somente por cenário controlado em teste descartável, mantendo operação indisponível à API. ArbitrarEncerramento modela a escolha pura: decisão APROVADA/REJEITADA correspondente devolve DECISAO_EXISTENTE com outputEventId original; identidade divergente é conflito seguro; ausência devolve BLOQUEIO_NECESSARIO, sem confirmação externa. Não está ligada ao listener/caso de uso persistente; não grava bloqueio/outbox nem habilita FALHOU. Autoridade/motivo operacional e evidência permanente continuam condicionados antes de qualquer ativação. A regra será usada sob lock pelo adapter durável seguinte.
