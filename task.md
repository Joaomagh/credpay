# CredPay — Tarefas

> Um incremento = um resultado verificável. Prioridades em docs/BACKLOG.md; evidências em spec.md.

## Último incremento

- Ensaio local de contadores com João: imagens f412652, Prepare/Activate/Demo com Diagnostics passaram, confirmed delta2 nos dois apps e APROVADA/REJEITADA/replay. Evidência e limites em spec9.135; não equivale a diagnóstico local de returned/error ou entendimento autônomo.

## Agora

- [ ] Corrigir a comparação de identidade da carga local Owned. PR196/b269fe3 resolveu arquivo OCI parcial; importação passou, mas runtime-ids comparava índice Docker com config CRI. João informou os quatro IDs; hashes dos manifestos/configs no TAR confirmam a correspondência amd64. Cadeia origem/plataforma/config/CRI em revisão/CI; depois João executa LoadImages, Validate e Remove uma ação por vez. Diagnóstico causal por métricas/prática pessoal continuam separados; candidato operacional será reidentificado ao fechar.

## Próximo

- João pediu adiar prática/explicação em22/10. Preservar o aceite de aprendizado pendente sem novos exercícios nesta execução; continuar trabalho técnico independente. Diagnóstico por métricas e Kubernetes local conservam seus limites/autorização.
- Se João trouxer erro local ou dúvida, tratar o menor problema demonstrado com teste quando aplicável.
- Após os aceites restantes, conferir aplicabilidade do corte técnico, atualizar somente o necessário e registrar fechamento sem alegações além da evidência.
- João pediu planejar demo pública gratuita após os aceites atuais. Opções/limites em docs/PUBLIC_DEMO_PLAN.md; destino e publicação externos ainda não autorizados especificamente. Não substituir o aceite local por deploy ou alterar a arquitetura para encaixar cota sem revisão.

## Depois

- Manutenção de runtime: avaliar imagem oficial atualizada para correções disponíveis de curl/zlib; GnuTLS88648 permanece sem correção, sem dispensa ou promoção automática. Findings preservados na triagem.
- Pesquisa de sandbox incompleta e painel adiados; referências frontend preservadas. Sem pagamento real, HA ou implantação externa.
