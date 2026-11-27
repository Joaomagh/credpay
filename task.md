# CredPay — Tarefas

> Um incremento = um resultado verificável. Direção em CREDPAY_PLAN.md; prioridades em docs/BACKLOG.md; evidências em spec.md. Uma única frente de implementação.

## Último incremento

- 26/11/2026 — C01 integrado na PR248/main `e54cd39`: API e tela de clientes, V12, isolamento e reinício. Head `d003f93` com17 checks verdes,651 testes Java,81 Angular+2 controles Node e10 jornadas reais. QA manual autenticado não realizado sem API local; CI cobriu o fluxo. spec§9.176.
- 26/11/2026 — C00: direção aprovada para **Gestão de cobranças e acompanhamento de recebimentos**, com público geral e nutricionista como possível primeiro caso, ainda sem validação real. Plano, backlog, apresentação e papéis alinhados; nenhuma funcionalidade nova entregue. Decisões e limites em spec§1.1/§9.175.
- Base anterior: simulador com contas, Angular, processamento assíncrono, recuperação e CI. Evidências históricas em spec§9.142–9.174, docs/V1_VALIDATION.md e docs/V1_CANDIDATE.md; aceite pessoal da v1 permanece adiado.

## Agora

- [ ] C07 pronto para iniciar — Google escolhido por João. Primeiro recorte: teste do vínculo estável `(issuer,subject)` com uma conta local, sem identificação por e-mail ou senha fictícia para identidade externa. Depois baseline, Authorization Code/PKCE, principal/sessão e tela. Nenhuma dependência OAuth2 ou credencial adicionada. Plano de testes e riscos no backlog/spec§9.176.

Execução ativa autorizada em 26/11: 18:37:41–23:37:41 (`America/Sao_Paulo`), cinco horas. Driver mantém implementação única; revisão/P.O. em paralelo conforme os papéis. Branch `feat/gestao-cobrancas` criada a partir da main local, preservando `feat/postgres-alpine-restore` e seu checkpoint. No fim da janela, deixar evidências e uma próxima ação recuperável.

Houve interrupção por limite de uso aproximadamente às19:43 e retomada às22:48 a pedido de João. A janela autorizada não equivale a cinco horas de execução contínua.

## Próximo

- C07: João escolheu Entrar com Google; conta e credenciais configuradas por ele. Após C01, começar pelo teste do vínculo `(issuer,subject)` com a conta local, depois baseline/fluxo OAuth2-OIDC e Angular. Preservar sessão e propriedade dos clientes; não vincular por e-mail, simular senha local ou armazenar tokens no Angular. Nenhuma integração externa criada.
- C02: criar cobrança com cliente da mesma conta, descrição, BRL, vencimento e idempotência. Depois C03/C04: consulta por situação e recebimento integral manual com histórico. Refinar o contrato de cada incremento antes do TDD.
- Validar a rotina e a dificuldade de uma pessoa do público quando João trouxer acesso/feedback; não presumir entrevista ou uso pela nutricionista. Integração de provedor será posterior, em ambiente de testes.

## Pendências preservadas da base

- Candidata PostgreSQL PR244: os ZIPs do run 44958057682 registraram pacotes corrigidos e zero achados; inventário geral/revisão e restauração em volume novo ainda exigem evidências antes de promover a imagem ativa. No checkout inicial desta sessão, a branch `feat/postgres-alpine-restore` tinha o commit `486d203` de teste de restauração; ele não foi reexecutado nem integrado por este incremento documental. Estados históricos e critérios em spec§9.174 e docs/MVP_CLOSURE_RUNBOOK.md. Nenhuma troca de imagem ou remoção de volumes.
- Preservar inventários/achados do banco e GnuTLS; revalidar o corte aplicável antes de publicação, sem inferir atualidade das contagens antigas. S11/S12–S14 CLOSED/FIXED e ocorrências contextuais continuam nos registros por ID.
- Seis riscos Sonar da PR243 continuam sem triagem individual completa: a página exige Advanced Security no plano disponível. PR246/head `15ae104` passou Angular, Compose, Secret Scan e Sonar, com `npm audit` completo em zero; isso não comprova encerramento dos IDs Sonar.
- Publicação gratuita: HTTPS, retenção, limites e recuperação no destino continuam pendentes. Contratos Workers locais passaram na PR243/42cc931; contas Cloudflare/Oracle dependem de João. Nenhum deploy, gasto, dinheiro real ou dados reais autorizado por esta evolução.
- Ensaio autônomo e decisão final da v1 permanecem adiados. Painel e pesquisa de sandbox continuam adiados, sem aceite/liberação. A evolução de produto não encerra esses itens.
