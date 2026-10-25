# CredPay — Tarefas

> Um incremento = um resultado verificável. Prioridades em docs/BACKLOG.md; evidências em spec.md.

## Último incremento

- João concluiu Validate no Kubernetes local em25/10: imagens, startup, persistência após recriação dos Pods, APROVADA/REJEITADA/replay e recuperação causal passaram. Remove também passou: somente o cluster próprio removido, Compose e volumes preservados. Evidência e limites em spec9.136.6.

## Agora

- [ ] Concluir revisão/CI do diagnóstico causal descartável no Compose: returned nos dois publicadores, PENDENTE/outbox original e recuperação com prova causal. Controles offline passaram, incluindo recusa Smoke sem mutação e config alterada antes de cleanup. Roteiro em infra/compose/DIAGNOSTICS.md; somente após integração João executa Run e depois Remove separadamente. Aceite local/prática pessoal continuam separados; candidato será reidentificado ao fechar.

## Próximo

- João pediu adiar prática/explicação em22/10. Preservar o aceite de aprendizado pendente sem novos exercícios nesta execução; continuar trabalho técnico independente. Diagnóstico por métricas e Kubernetes local conservam seus limites/autorização.
- Se João trouxer erro local ou dúvida, tratar o menor problema demonstrado com teste quando aplicável.
- Após os aceites restantes, conferir aplicabilidade do corte técnico, atualizar somente o necessário e registrar fechamento sem alegações além da evidência.
- João pediu planejar demo pública gratuita após os aceites atuais. Opções/limites em docs/PUBLIC_DEMO_PLAN.md; destino e publicação externos ainda não autorizados especificamente. Não substituir o aceite local por deploy ou alterar a arquitetura para encaixar cota sem revisão.

## Depois

- Manutenção de runtime: avaliar imagem oficial atualizada para correções disponíveis de curl/zlib; GnuTLS88648 permanece sem correção, sem dispensa ou promoção automática. Findings preservados na triagem.
- Pesquisa de sandbox incompleta e painel adiados; referências frontend preservadas. Sem pagamento real, HA ou implantação externa.
