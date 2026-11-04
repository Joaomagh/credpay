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

## Agora

- [ ] Aceite pessoal da v1 permanece adiado. O roteiro está em docs/DEMO.md e as evidências assistidas em docs/V1_VALIDATION.md.
- [ ] Criar transações no perfil de contas e isolar a idempotência por conta e chave.

## Próximo

- Depois dos aceites pessoais, conferir a aplicabilidade das evidências e registrar a decisão final sobre o candidato técnico 51f8476.
- Acompanhar os achados GnuTLS registrados no inventário e na triagem.
- Console autenticado com troca de sessão, recuperação de intenção por conta e teste com duas contas.

## Depois

- Demo pública gratuita após escolher e autorizar o destino. Critérios de publicação em docs/PUBLIC_DEMO_PLAN.md.
- Painel e pesquisa de sandbox seguem adiados.
