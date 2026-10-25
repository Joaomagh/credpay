# CredPay — Tarefas

> Um incremento = um resultado verificável. Prioridades em docs/BACKLOG.md; evidências em spec.md.

## Último incremento

- Ambiente mínimo Windows integrado pela PR198/db363eb após revisão e CI verdes. João reconferiu o contexto: via process igual=True; via native exit=0, partes=1, igual=True. Carga das quatro imagens já comprovada; evidências e limites em spec9.136.4/5.

## Agora

- [ ] João executa Validate no cluster próprio; após conferir o resultado, Remove separadamente. Contexto passou após a correção; startup/fluxo/cleanup locais ainda sem aceite. Diagnóstico causal por métricas/prática pessoal continuam separados; candidato operacional será reidentificado ao fechar.

## Próximo

- João pediu adiar prática/explicação em22/10. Preservar o aceite de aprendizado pendente sem novos exercícios nesta execução; continuar trabalho técnico independente. Diagnóstico por métricas e Kubernetes local conservam seus limites/autorização.
- Se João trouxer erro local ou dúvida, tratar o menor problema demonstrado com teste quando aplicável.
- Após os aceites restantes, conferir aplicabilidade do corte técnico, atualizar somente o necessário e registrar fechamento sem alegações além da evidência.
- João pediu planejar demo pública gratuita após os aceites atuais. Opções/limites em docs/PUBLIC_DEMO_PLAN.md; destino e publicação externos ainda não autorizados especificamente. Não substituir o aceite local por deploy ou alterar a arquitetura para encaixar cota sem revisão.

## Depois

- Manutenção de runtime: avaliar imagem oficial atualizada para correções disponíveis de curl/zlib; GnuTLS88648 permanece sem correção, sem dispensa ou promoção automática. Findings preservados na triagem.
- Pesquisa de sandbox incompleta e painel adiados; referências frontend preservadas. Sem pagamento real, HA ou implantação externa.
