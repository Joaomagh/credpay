# CredPay — Tarefas

> Um incremento = um resultado verificável. Prioridades em docs/BACKLOG.md; evidências em spec.md.

## Último incremento

- LoadImages passou no Docker Desktop de João após PR197/50a50a4: quatro imagens amd64 carregadas e cadeia de identidade conferida. Validate confirmou novamente as quatro imagens; startup parou na chamada nativa do contexto, antes dos recursos. Evidências e limites em spec9.136.4/5.

## Agora

- [ ] Concluir revisão/CI do ambiente mínimo Windows: PATHEXT explícito para .exe, home dos filhos no temp próprio e cache contido. Controle sem Docker reproduziu a falha; 78 controles Windows passaram após ajuste. Depois João reconfere contexto e executa Validate/Remove uma ação por vez. Diagnóstico causal por métricas/prática pessoal continuam separados; candidato operacional será reidentificado ao fechar.

## Próximo

- João pediu adiar prática/explicação em22/10. Preservar o aceite de aprendizado pendente sem novos exercícios nesta execução; continuar trabalho técnico independente. Diagnóstico por métricas e Kubernetes local conservam seus limites/autorização.
- Se João trouxer erro local ou dúvida, tratar o menor problema demonstrado com teste quando aplicável.
- Após os aceites restantes, conferir aplicabilidade do corte técnico, atualizar somente o necessário e registrar fechamento sem alegações além da evidência.
- João pediu planejar demo pública gratuita após os aceites atuais. Opções/limites em docs/PUBLIC_DEMO_PLAN.md; destino e publicação externos ainda não autorizados especificamente. Não substituir o aceite local por deploy ou alterar a arquitetura para encaixar cota sem revisão.

## Depois

- Manutenção de runtime: avaliar imagem oficial atualizada para correções disponíveis de curl/zlib; GnuTLS88648 permanece sem correção, sem dispensa ou promoção automática. Findings preservados na triagem.
- Pesquisa de sandbox incompleta e painel adiados; referências frontend preservadas. Sem pagamento real, HA ou implantação externa.
