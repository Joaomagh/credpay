# CredPay — Tarefas

> Um incremento = um resultado verificável. Prioridades em docs/BACKLOG.md; evidências em spec.md.

## Último incremento

- Ensaio local de contadores com João: imagens f412652, Prepare/Activate/Demo com Diagnostics passaram, confirmed delta2 nos dois apps e APROVADA/REJEITADA/replay. Evidência e limites em spec9.135; não equivale a diagnóstico local de returned/error ou entendimento autônomo.

## Agora

- [ ] Retomar a sessão local preservada em Creating, após revisão/CI de Resume explícito e ID literal do node conferido por João. PR194/2fd3d58 corrigiu label=disable; João confirmou política completa, daemon original e kubeconfig em leitura. Resume está em testes: registro original/ID/política/TLS/imagens antes da promoção, sem create/delete ou edição manual. Depois conduzir Validate e Remove uma ação por vez. Diagnóstico causal por métricas/prática pessoal continuam separados; candidato operacional será reidentificado ao fechar.

## Próximo

- João pediu adiar prática/explicação em22/10. Preservar o aceite de aprendizado pendente sem novos exercícios nesta execução; continuar trabalho técnico independente. Diagnóstico por métricas e Kubernetes local conservam seus limites/autorização.
- Se João trouxer erro local ou dúvida, tratar o menor problema demonstrado com teste quando aplicável.
- Após os aceites restantes, conferir aplicabilidade do corte técnico, atualizar somente o necessário e registrar fechamento sem alegações além da evidência.
- João pediu planejar demo pública gratuita após os aceites atuais. Opções/limites em docs/PUBLIC_DEMO_PLAN.md; destino e publicação externos ainda não autorizados especificamente. Não substituir o aceite local por deploy ou alterar a arquitetura para encaixar cota sem revisão.

## Depois

- Manutenção de runtime: avaliar imagem oficial atualizada para correções disponíveis de curl/zlib; GnuTLS88648 permanece sem correção, sem dispensa ou promoção automática. Findings preservados na triagem.
- Pesquisa de sandbox incompleta e painel adiados; referências frontend preservadas. Sem pagamento real, HA ou implantação externa.
