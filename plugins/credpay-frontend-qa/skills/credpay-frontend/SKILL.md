---
name: credpay-frontend
description: Desenvolver e revisar a experiência Angular do CredPay como produto de gestão de cobranças, com autenticação Google, clientes, acessibilidade, temas e revisão responsiva; preservar regressões do simulador legado quando afetadas.
---

# Frontend do CredPay

Leia `AGENTS.md`, `task.md` e o [guia do frontend](../../../../frontend/README.md) no checkout ativo. Consulte o [contrato de cobranças](../../../../docs/BILLING_CONTRACT.md) antes de alterar essa jornada. Se instalada fora do repositório, resolva os documentos pela raiz do checkout.

## Direção do produto

CredPay organiza clientes, cobranças e recebimentos de profissionais e pequenos negócios. Priorize a tarefa do usuário. Use o backlog para distinguir o que funciona do que ainda será entregue; não invente saldo, métricas, cobrança paga, confirmação bancária ou ações sem API real.

- O perfil padrão exige sessão. `/entrar` cadastra ou autentica via Google; o primeiro acesso cria conta vazia, sem popular exemplos. Google é a ação principal, com imagem oficial local e explicação clara do cadastro. A alternativa /criar-conta cadastra com e-mail/senha/confirmação, mínimo8 escolhido por João; entrada existente por e-mail ou usuário permanece secundária. Resposta202 genérica orienta entrar; não autenticar automaticamente, não prometer verificação de e-mail ou recuperação ainda ausentes. Consulte docs/EMAIL_REGISTRATION.md.
- Após autenticar, encaminhe à área útil já disponível, atualmente Clientes. Não interrompa a jornada com tela de sessão, botão Abrir console ou identificador `google-...`. Não exiba UUIDs de conta/cliente por conveniência técnica; IDs seguem nos contratos internos.
- O simulador permanece compatível em rotas anteriores e configuração `legado`; ele não é a navegação principal do produto. Preserve seus avisos e semântica nos fluxos próprios. Não rebatize aprovação fictícia como recebimento.
- A interface principal usa linguagem de negócio, sem badges Demo, detalhes da stack ou avisos repetidos de dados fictícios. Documentação mantém os limites reais de publicação, uso e pagamentos. Remover copy antiga não autoriza dados reais nem equivale a implantar produção.

## Critérios visuais

- Preserve identidade verde e use os tokens compartilhados de `src/styles.css`. Escuro é o padrão; controle claro/escuro usa somente sol/lua com nome acessível e escolha persistida localmente. A interface continua utilizável se armazenamento estiver bloqueado.
- Dê hierarquia à ação principal; revise alinhamento, largura de leitura, margens e espaços entre formulário, ajuda e botões. Prefira estados vazios úteis a conteúdo de exemplo automático.
- Confira contraste nos dois temas, foco de teclado, rótulos de campos, mensagens associadas e alvos de pelo menos44px. No celular, navegação não pode cobrir campos, conteúdo ou ações. Restaure overrides de viewport ao concluir.
- Use ícones existentes/SVG local e assets oficiais para marcas de terceiros. Consulte as [diretrizes Google](https://developers.google.com/identity/branding-guidelines) se alterar o botão; preserve cores/proporções do G. Não introduza CDN, SDK de login paralelo, fonte ou framework visual sem necessidade aprovada.

## Verificação da jornada

Comportamento novo começa por teste red conforme AGENTS. Reutilize testes/componentes e rode `npm.cmd run check`, `npm.cmd test`, `npm.cmd run build`; se afetar navegação, rode tipos/listagem E2E e preserve cenários de isolamento, CSRF, saída e recuperação.

1. Abra uma aba própria em `/entrar` no servidor local existente. Inicie `npm.cmd start` somente se necessário e com dependências presentes. Mudança de assets/configuração pode exigir reiniciar o dev server. Não interrompa processos do usuário sem coordenar.
2. Confira login Google disponível, cadastro explicado, e-mail/senha secundários, link de cadastro e erro acionável. Teste senha mínima8, confirmação divergente, limite429 e limpeza das senhas após envio. Não capture segredo, cookie, código OAuth ou URL de retorno. Credenciais/aceites pessoais permanecem com João; siga GOOGLE_SETUP.md.
3. Com sessão válida, confira encaminhamento direto, cadastro/listagem/consulta de cliente e saída. Mesmo Google deve recuperar os dados; segunda identidade não acessa os da primeira. Separe testes de fronteira HTTP de evidência com backend real.
4. Revise entrada e área autenticada em tela estreita e larga, claro/escuro, teclado e após reload. Não use execução Docker local pelo agente: respeite AGENTS e os guards dos E2E de CI.
5. Relate o que passou, falhou e ficou pendente. Não transforme inspeção visual em prova de persistência ou testes fictícios em aceite do Google real. Atualize task/spec/backlog conforme o incremento.

## Regressões do legado quando afetadas

Preserve relação POST/GET: criação retorna recibo original PENDENTE, consulta mostra estado atual. Dinheiro usa representação decimal exata, sem Number. Falha ambígua e reload preservam chave/payload; retry não cria intenção nova automaticamente. Revalide a sessão e descarte respostas de outra conta. Use `frontend/e2e/console.spec.ts` e `contas.spec.ts`; não reduza assertions, retries ou deadlines para passar. Os cenários antigos continuam importantes mesmo fora da navegação principal.
