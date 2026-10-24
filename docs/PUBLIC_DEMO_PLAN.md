# Demonstração pública do CredPay — opções e recorte

Pesquisa em 24/10/2026, a pedido de João. **Planejamento para depois do aceite local em andamento; destino não escolhido e nenhum deploy, conta, gasto ou recurso externo criado.** Links e limites precisam ser reconferidos antes da contratação/publicação. A exceção de Kubernetes local não autoriza implantação externa nem acesso do agente ao daemon.

## Recomendação

Começar pelo Angular estático no **Netlify Free**, com URL do provedor e integração de build restrita ao frontend. Vercel Hobby e Cloudflare Pages também são candidatos. Para o backend real, avaliar uma VM com containers continuamente ativos; com orçamento zero, Oracle Always Free é uma candidata de prova de conceito, sem garantia de capacidade/disponibilidade. A escolha final depende de um teste da pilha completa e das condições da conta de João.

Uma demonstração acessível pode facilitar a apresentação do trabalho. Isso é julgamento de produto, não promessa de contratação. Acompanhar o link com roteiro curto, vídeo de contingência e README/testes/limites verificáveis; resultado simulado, se houver modo ilustrativo futuro, precisa ser rotulado e não substituir o backend real.

## Frontend

| Opção | Adequação à demo | Limite relevante conferido |
|---|---|---|
| Netlify Free — primeira candidata | Angular estático, redirects e domínio do provedor | 300 créditos/mês, limite rígido sem recarga automática; pode pausar até renovar a franquia |
| Vercel Hobby | Alternativa para projeto pessoal não comercial | Uso pessoal/não comercial e cotas próprias; excedê-las pode suspender a funcionalidade |
| Cloudflare Pages Free | Alternativa para conteúdo estático | 500 builds/mês e20 min por build; funções têm cotas separadas |

Fontes: [Angular no Netlify](https://docs.netlify.com/build/frameworks/framework-setup-guides/angular/), [Netlify Free](https://docs.netlify.com/manage/accounts-and-billing/billing/billing-for-credit-based-plans/credit-based-pricing-plans/), [Vercel Hobby](https://vercel.com/docs/plans/hobby), [Cloudflare Pages](https://developers.cloudflare.com/pages/platform/limits/).

Publicar o frontend sozinho não disponibiliza os dois serviços Java. O proxy de desenvolvimento do Angular também não é configuração de produção. Precisaremos testar build estático, fallback de `/nova` e `/consultar`, destino HTTPS e proxy/CORS restritos, mantendo URL pública da API separada de senhas e conexões de banco/broker. Segredos não entram no bundle Angular.

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

1. Escolher conta/região/plano e um orçamento máximo explícito com João; preservar teto zero quando essa for a opção, sem ativar upgrade/recarga/serviço pago automaticamente. Confirmar disponibilidade antes de adaptar arquitetura.
2. Provar os dois serviços, bancos e broker no destino escolhido: primeiro acesso após ociosidade, POST/PENDENTE→GET final, replay e recuperação. Se houver suspensão, a interface precisa explicá-la antes de enviar o pedido; não declarar disponibilidade contínua.
3. Definir HTTPS/ingress para a API pública, limites de requisição/armazenamento e ciclo dos dados fictícios. Bancos, AMQP/management e métricas administrativas ficam internos. Revalidar dependências/imagens/achados no ambiente real; não copiar o perfil Diagnostics para exposição pública.
4. Preparar configuração, testes e rollback revisáveis; só então obter autorização específica para a publicação no destino. Fazer smoke externo em desktop/mobile e conferir rotas, idempotência e decimal exato. Nenhum domínio pago ou Kubernetes cloud é requisito automático.

O objetivo é uma demo honesta que o visitante consiga abrir e entender. Deploy não comprova aprendizado de João, não encerra pendências de segurança e não transforma dados fictícios em um serviço financeiro real.
