# CredPay — Tarefas

> Um incremento = um resultado verificável. Prioridades em docs/BACKLOG.md e evidências em spec.md.

## Último incremento

- João concluiu o diagnóstico causal local com as imagens f412652. Os dois publicadores recuperaram o evento original após retorno sem rota, sem duplicação. O ensaio confirmou persistência após down/up e remoção exclusiva dos recursos de teste.
- A apresentação do projeto e o relatório do backlog foram atualizados em README.md e docs/ENTENDA_O_CREDPAY.md.

- QA manual integrado concluído com o backend local ativo: aprovação, rejeição, UUID ausente e retomada do recibo. Evidência em spec§9.139.1.
- Manutenção de runtime Temurin 21 Noble integrada na PR #205 após oito checks. Inventário e corte técnico registrados em docs/V1_VALIDATION.md e docs/V1_CANDIDATE.md.
- Limite de 8 KiB na criação HTTP, com red reproduzido e regressão na PR #207. Proposta de contas individuais e seus critérios no backlog.
- Primeiro recorte de sessão local com contas fictícias na PR #208. Evidência e limites em spec§9.142.
- Consulta por conta no perfil de sessão na PR #209. Contrato, migração e teste de regressão em spec§9.143.
- Criação e idempotência por conta na PR #210. Contrato e regressão em spec§9.144.
- Tela de entrada e saída com contexto de sessão na PR #211. Evidência e recorte em spec§9.145.
- Rotas autenticadas, recibos por conta e proteção contra contexto divergente na PR #212. Evidência em spec§9.146.
- Jornada com duas contas no navegador e na pilha real exercitada pelo Compose CI. Roteiro em spec§9.147.

## Agora

- [ ] Aceite pessoal da v1 permanece adiado. O roteiro está em docs/DEMO.md e as evidências assistidas em docs/V1_VALIDATION.md.
- [ ] Limitar expansão de valores monetários compactos e definir limites de abuso para a demo com contas.

## Próximo

- Depois dos aceites pessoais, conferir a aplicabilidade das evidências e registrar a decisão final sobre o candidato técnico 51f8476.
- Acompanhar os achados GnuTLS registrados no inventário e na triagem.
- Critérios de publicação da demo com contas.

## Depois

- Demo pública gratuita após escolher e autorizar o destino. Critérios de publicação em docs/PUBLIC_DEMO_PLAN.md.
- Painel e pesquisa de sandbox seguem adiados.
