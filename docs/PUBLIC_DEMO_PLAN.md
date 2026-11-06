# Demonstração pública do CredPay — opções e recorte

Pesquisa em 24/10/2026, a pedido de João. **Planejamento para depois do aceite local em andamento; destino não escolhido e nenhum deploy, conta, gasto ou recurso externo criado.** Links e limites precisam ser reconferidos antes da contratação/publicação. A exceção de Kubernetes local não autoriza implantação externa nem acesso do agente ao daemon.

## Recomendação

Em 04/11/2026, a primeira candidata para o Angular com contas passou a ser **Cloudflare Workers Free com Static Assets**. O mesmo endereço público pode servir o Angular e encaminhar apenas as rotas da API ao backend. Para o backend, testar **uma VM Oracle Always Free A1** com os dois serviços Java, PostgreSQL e RabbitMQ em Compose. A documentação informa até 2 OCPUs e 12 GB de RAM no plano Always Free. A prova depende de capacidade regional, compatibilidade ARM64 e validação do HTTPS também entre o proxy e a origem. A Oracle pode recolher VMs ociosas. [Cloudflare SPA](https://developers.cloudflare.com/workers/static-assets/routing/single-page-application/) · [Oracle Always Free](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm)

Uma demonstração acessível pode facilitar a apresentação do trabalho. Isso é julgamento de produto, não promessa de contratação. Acompanhar o link com roteiro curto, vídeo de contingência e README/testes/limites verificáveis; resultado simulado, se houver modo ilustrativo futuro, precisa ser rotulado e não substituir o backend real.

## Frontend

| Opção | Adequação à demo | Limite relevante conferido |
|---|---|---|
| Cloudflare Workers Free com Static Assets — primeira candidata | Angular com fallback de SPA e proxy da API na mesma origem pública | Arquivos estáticos têm requisições gratuitas. As chamadas que invocam o Worker têm limite de 100 mil por dia e 10 ms de CPU por chamada |
| Netlify Free | Angular estático, redirects e domínio do provedor | 300 créditos/mês, limite rígido sem recarga automática; pode pausar até renovar a franquia |
| Vercel Hobby | Alternativa para projeto pessoal não comercial | Uso pessoal/não comercial e cotas próprias; excedê-las pode suspender a funcionalidade |
| Cloudflare Pages Free | Alternativa para conteúdo estático | 500 builds/mês e20 min por build; funções têm cotas separadas |

Fontes: [Cloudflare Workers](https://developers.cloudflare.com/workers/platform/pricing/), [Angular no Netlify](https://docs.netlify.com/build/frameworks/framework-setup-guides/angular/), [Netlify Free](https://docs.netlify.com/manage/accounts-and-billing/billing/billing-for-credit-based-plans/credit-based-pricing-plans/), [Vercel Hobby](https://vercel.com/docs/plans/hobby), [Cloudflare Pages](https://developers.cloudflare.com/pages/platform/limits/).

Para a demo com contas, o ponto de entrada público precisa servir o Angular e encaminhar `/auth/**` e `/transacoes/**` na mesma origem HTTPS. A configuração deve dar fallback ao Angular em `/entrar`, `/nova` e `/consultar`, e preservar os cabeçalhos de sessão e contexto de conta nas rotas da API. O proxy de desenvolvimento atende ao ensaio local. Banco, broker e segredos ficam na infraestrutura do backend.

## Backend: onde o gratuito precisa ser comprovado

| Candidata | O que existe | Consequência para o CredPay |
|---|---|---|
| Oracle Always Free A1 | Documentação atual:2 OCPUs/12 GB totais Arm, com franquias de armazenamento próprias | Pode acomodar uma prova da pilha em VM, mas exige baseline/testes/inventário ARM64; há falta de capacidade por região e possível retomada de instância ociosa |
| Render Free + bancos/broker externos | Web services gratuitos dormem após 15 min sem tráfego HTTP/WebSocket;750 horas/mês compartilhadas; Postgres Free expira em 30 dias | O consumidor AMQP não é acordado por mensagem de fila. Dois serviços 24/7 excedem750 horas em um mês de 30 dias; cold start interfere no timeout de 8 s atual do console |
| Railway Free | Trial de 30 dias/US$ 5, depois franquia de US$ 1/mês; limites por serviço | Crédito não é garantia de manter a pilha inteira continuamente; medir custo/consumo antes de escolher |

Fontes: [Oracle Always Free](https://docs.oracle.com/en-us/iaas/Content/FreeTier/freetier_topic-Always_Free_Resources.htm), [Render Free](https://render.com/docs/free), [Railway](https://railway.com/pricing). A adequação à aplicação é inferência de engenharia, não teste de deploy já realizado. Não prometer backend gratuito 24/7 ou gerar tráfego artificial para contornar limites dos provedores.

Na VM candidata, preservar os dois aplicativos, propriedade separada dos dados e RabbitMQ real; Compose pode reduzir operação para essa demo, mantendo Kubernetes como evidência local/CI. Isso é proposta, não decisão de implantação. A baseline atual foi provada em linux/amd64; Java portável não comprova imagens, bibliotecas nativas e inventário ARM64.

Como alternativas de dados, [Neon Free](https://neon.com/pricing) informa 100 projetos, 1 GB e 100 CU-h por projeto, com suspensão por inatividade. O polling periódico da outbox pode manter o banco ativo mesmo sem visitantes; medir esse consumo antes de supor que o projeto ficará dentro da franquia. [CloudAMQP Little Lemur](https://www.cloudamqp.com/plans/) informa RabbitMQ compartilhado gratuito com cotas. **Compatibilidade das quatro filas quorum, políticas, permissões, vhost, TLS e limites do broker ainda não foi comprovada nesse plano.** AMQP compatível não equivale a aceitar toda a baseline; não trocar quorum por classic ou substituir o broker por LavinMQ silenciosamente para caber no gratuito.

Vercel também documenta runtime de containers em beta. Portanto, não é correto descartar Java por linguagem; custo, duração, modelo de execução e suporte ao consumidor persistente precisariam de uma prova própria antes de escolher essa rota. [Runtimes oficiais](https://vercel.com/docs/functions/runtimes).

## Incremento público após o fechamento local

Preparação técnica em05/11, sem deploy: os índices exatos já fixados de Temurin, PostgreSQL e RabbitMQ contêm variantes Linux/ARM64. A tag atual PostgreSQL17.11-bookworm mudou, mas o índice fixado051f7b7b continua contendo o manifesto ARM64b260573038b3770e3d1aba5a9a033bdd19c4b16304b104d4b4256c75d8f15123; não substituir o pin por tag móvel. Foram consultados somente metadados oficiais, sem executar imagens ARM. [PostgreSQL oficial](https://hub.docker.com/_/postgres) · [RabbitMQ oficial](https://hub.docker.com/_/rabbitmq).

Próximo aceite: build, fluxo/autenticação e inventário completos em ARM64 nativo, preservando o perfil AMD64 e os guards locais de kind. Metadados multiarch não comprovam esse aceite. Contas Cloudflare/Oracle, capacidade regional, HTTPS da origem, configuração privada/expiração dos dados e smoke externo continuam pendentes; nenhuma URL pública foi criada. As correções Sonar integradas nas PRs227/228 não substituem esses testes ou a autorização específica do destino.

1. Escolher conta/região/plano e um orçamento máximo explícito com João; preservar teto zero quando essa for a opção, sem ativar upgrade/recarga/serviço pago automaticamente. Confirmar disponibilidade antes de adaptar arquitetura.
2. Provar os dois serviços, bancos e broker no destino escolhido: primeiro acesso após ociosidade, POST/PENDENTE→GET final, replay e recuperação. Se houver suspensão, a interface precisa explicá-la antes de enviar o pedido; não declarar disponibilidade contínua.
3. Definir HTTPS e cookie `Secure`, provisionamento das contas, expiração de sessões e ciclo dos dados fictícios. Os limites do perfil local valem por instância. Conferir a identidade de origem no proxy e as cotas no destino. Bancos, AMQP, management, diagnósticos e métricas administrativas ficam internos. Revalidar dependências, imagens e achados no ambiente real.
4. Preparar configuração, testes e rollback revisáveis; só então obter autorização específica para a publicação no destino. Fazer smoke externo em desktop/mobile e conferir rotas, idempotência e decimal exato. Nenhum domínio pago ou Kubernetes cloud é requisito automático.

O objetivo é uma demo honesta que o visitante consiga abrir e entender. Deploy não comprova aprendizado de João, não encerra pendências de segurança e não transforma dados fictícios em um serviço financeiro real.
