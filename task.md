# CredPay — Tarefas

> Uma frente de implementação. Direção em CREDPAY_PLAN.md; prioridades em docs/BACKLOG.md; evidências em spec.md; quadro em docs/DELIVERY_STATUS.md.

## Último incremento

- C07 integrado na PR250/main df45ca5 após gates verdes no head29c8150: Maven699/0,83 Angular+2 Node,96 contratos Worker+4 runtime, Compose/Flow/imagens/inventários e Sonar89,8%/0 apontamentos novos. Google real e hospedagem ainda não configurados. spec§9.177.
- C01 integrado na PR248/main e54cd39: clientes por conta, persistência e tela. Houve intermitência posterior no primeiro cenário desktop; três ensaios recentes com instrumentação passaram. Causa não identificada; preservar diagnóstico, sem declarar correção causal.

## Agora

- [ ] C02 na branch feat/cobrancas: PedidoCobranca red23/green23; serviço red9/green9 e precisão temporal red1/green. Verify afetado46/0 e preflight33/0, sem Docker local. Contrato em docs/BILLING_CONTRACT.md; spec§9.178.
- Próxima ação única: observar red real de CobrancaPersistenceIntegrationTest no CI antes de implementar V15/SQL. Adapter continua stub. Modo manual focado usa job/concorrência separados e não substitui os gates completos de PR/main.

## Próximo

- V15/adapter e provas de commit, constraints, concorrência, replay, conflito, isolamento e rollback. Depois HTTP/segurança e jornada Angular de criação.
- C03: consultar/listar abertas e vencidas com Clock/São Paulo. C04: recebimento integral manual único e histórico. Cada comportamento começa pelo teste.
- Ensaio Google real: docs/GOOGLE_SETUP.md; conta vazia no primeiro login, Google obrigatório na apresentação ao RH, sem consulta anônima. Projeto/credenciais são configuração pessoal, nunca valores no chat.

## Publicação e pendências preservadas

- Teto zero e sem domínio próprio. Cloudflare/Compose; k3s retirado da fila por João. Oracle Always Free é candidata recomendada, ainda sem cadastro/provisionamento; validação pessoal por cartão e eventual autorização temporária não foram realizadas. Endereços/TLS/retencão/recuperação ainda exigem prova no destino.
- Candidata PostgreSQL PR244 não promovida. Checkpoint486d203 em feat/postgres-alpine-restore preservado; restore de volume novo e inventários/revisão seguem em docs/MVP_CLOSURE_RUNBOOK.md. Nenhum acesso Docker local pelo agente ou remoção de volumes.
- Seis riscos históricos Sonar PR243 seguem sem triagem individual completa por limite do plano. Gate novo verde não encerra os IDs antigos nem aceita vulnerabilidades da base.
- Aceite pessoal da v1, uso real/feedback e lacunas históricas conservam seus estados nos documentos próprios. Execução interrompida por limites não equivale a cinco horas contínuas de trabalho.
