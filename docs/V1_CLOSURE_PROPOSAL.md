# Proposta de recorte para fechar a v1

Estado em18/10/2026: João escolheu explicitamente separar a pesquisa de sandbox da v1 do backend, preservando achados e sem aceitar/liberar o core experimental. Plano/backlog/README refletem esse recorte. A disposição histórica abaixo continua pendente e separada; a v1 não está declarada concluída.

## Resultado da avaliação

O fluxo financeiro, idempotência, outboxes, falhas/recuperação e Kubernetes hospedado possuem evidências integradas. A migração dos aplicativos para a plataforma corrigida foi realizada. Isso não equivale a comprovar execução no computador de João, aprendizado pessoal ou isolamento do agente.

A avaliação Ubuntu autorizada produziu inventário e controles reais, mas não demonstrou correção/não afetação de ACL e quatro IDs util-linux. O scan HIGH0/CRITICAL0 não encerra essa lacuna. A revisão das fontes de cp/mv/sed integrada na PR177 delimita caminhos, sem fornecer remediação suficiente. O agente atual continua fora da contenção; não se afirma que a sandbox esteja pronta.

## Opções de direção

| Direção | Benefício | Custo e aceite |
|---|---|---|
| Separar a pesquisa de sandbox do fechamento do backend — recomendação de produto | Concentra a entrega de portfólio no fluxo Java e no aprendizado demonstrável, preservando a pesquisa e todos os achados | Mudança explícita da fase1 e do marco sandbox da v1. Core diagnóstico permanece experimental e sem autorização de uso pelo agente; não vira ambiente aceito nem substitui as restrições de AGENTS.md |
| Manter sandbox completa como requisito da v1 | Preserva o plano atual e exige demonstração real de isolamento | V1 continua aberta. Próximo incremento é concluir aplicabilidade/configuração exata dos IDs restantes e apresentar composição/plataforma corrigida. Build próprio, nova distribuição ou topologia exigem proposta/decisão específica; não há prazo de conclusão demonstrado |

Separar o escopo não significa aceitar a candidata Ubuntu, ocultar CVEs ou usar o core vulnerável. Significa não distribuir esse experimento como ambiente pronto da v1 do backend. A proibição de segredos, acessos pessoais, Docker local privilegiado e implantação externa continua aplicável.

## Alterações autorizadas pela escolha de João

1. Atualizar somente os critérios de saída de CREDPAY_PLAN.md e docs/BACKLOG.md: sandbox do agente vira pesquisa posterior explicitamente incompleta; preservar ferramentas, CI, inventários e documentos existentes sem promoção da candidata.
2. Ajustar README/spec/task para comunicar backend de demonstração e limites, sem alegação de isolamento do agente. Frontend Angular e painel continuam posteriores.
3. Executar os checks aplicáveis no candidato final e reavaliar os inventários dos aplicativos no corte final, preservando achados/triagem. Correções e testes do backend não são dispensados.
4. Resolver a disposição da lacuna histórica de publicação separadamente; executar o ensaio guiado e os passos locais com João. Somente então avaliar fechamento da v1.

## Disposição histórica proposta, separada do escopo

Em18/10, nova consulta autorizada retornou total_count0 nos cinco runs históricos relacionados em infra/security/PUBLICATION_REVIEW.md. Nova leitura do job111053029171 voltou a falhar com Transport closed. Nenhum byte desses seis conteúdos foi revisto; não há conclusão de conteúdo limpo, exclusão ou expiração.

Proposta para decisão explícita: aceitar uma revisão de publicação delimitada aos conteúdos efetivamente acessíveis, manter os seis itens identificados como não verificados e reabri-los se recuperados. Não apagar evidências, ampliar a conclusão de ausência de segredos ou reescrever histórico. Isso aceita uma lacuna de evidência histórica; não aceita vulnerabilidades de dependências e não é uma revisão completa de publicação.

## O que continua dependendo de João

O ensaio de docs/DEMO.md já foi preparado a pedido de João. Preflight, execução local/Desktop e explicação do fluxo sem consultar código permanecem sem comprovação. Nenhum merge, scanner ou resposta de um agente substitui esse aceite pessoal.

## Por que há decisão aqui

AGENTS.md §1 reserva mudança de objetivo/escopo ao Navigator. CREDPAY_PLAN.md ainda exige a sandbox na fase1. A triagem do core proíbe trocar distribuição ou compilar patches automaticamente. PUBLICATION_REVIEW.md exige recuperar os conteúdos ou registrar disposição específica para ampliar sua conclusão. As opções acima tornam as decisões revisáveis; nenhuma foi presumida a partir do pedido de trabalhar em looping.
