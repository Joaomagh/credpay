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

## Estado e conclusão

Contrato preliminar revisável, sem enum, migration, endpoint, AMQP ou dependência novos. B06.7 encerra observação de retornos; não conclui FALHOU, dashboard, Kubernetes ou sandbox. Percentual global permanece estimativa aproximada de 65%; critérios de saída governam a conclusão.
