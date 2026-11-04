---
name: credpay-frontend
description: Desenvolver, revisar e testar manualmente o console Angular do CredPay, incluindo criação, consulta, recuperação de intenção e revisão responsiva no navegador local.
---

# Frontend do CredPay

Leia `AGENTS.md` e o [guia do frontend](../../../../frontend/README.md) no checkout do CredPay. Se esta skill estiver instalada fora do repositório, abra `frontend/README.md` a partir da raiz do checkout ativo.

## Alterações

- Preserve a relação entre POST e GET. O POST devolve o recibo original `PENDENTE`, inclusive em replay. A consulta por UUID mostra o estado atual.
- Preserve a chave e o valor da intenção em falha ambígua ou reload. Retry reutiliza a mesma intenção. Nova intenção exige ação explícita. Trabalhe com decimal exato, sem conversão monetária para `Number`.
- Escreva e execute o menor teste red antes de corrigir um comportamento. Use os testes e componentes existentes. Rode `npm.cmd run check`, `npm.cmd test` e `npm.cmd run build` em `frontend/` quando alterar código.

## Teste manual

1. Use o navegador integrado ou o plugin Computer Use para abrir `http://127.0.0.1:4200/nova` em uma aba de teste própria. Se o frontend estiver parado e as dependências já estiverem presentes, inicie com `npm.cmd start` em `frontend/`. Verifique o health local da API antes dos casos integrados. Respeite as autorizações de infraestrutura em `AGENTS.md`.
2. Confira as rotas Nova transação e Consultar. Envie valor zero e UUID inválido e confira a mensagem associada ao campo ou à operação. Teste teclado, indicação de foco e viewport estreito e largo. Se ajustar o viewport, restaure-o ao final.
3. Com a API ativa e dados fictícios, use um valor positivo dentro do limite configurado e outro acima. Confira recibo, navegação para consulta, UUID estável e estado final esperado. Recarregue a criação na mesma aba e confirme que a intenção ou o recibo original foi recuperado. Se houver acesso à rede do navegador, verifique também que o reload não envia outro POST. Consulte um UUID válido ausente e confira o estado de ausência.
4. Com a API indisponível, use no máximo um envio fictício para conferir o tratamento do erro, a opção de repetir com a mesma intenção e a restauração após reload. Marque os casos integrados como bloqueados. Use o [roteiro de demo](../../../../docs/DEMO.md) no checkout para ensaios de falha já autorizados.
5. Relate o que passou, falhou ou ficou bloqueado, com passos curtos e o escopo observado. Separe evidência visual, resposta da API e checagem de rede. Use os cenários de navegador existentes em `frontend/e2e/` como referência de regressão. Registre somente dados de teste necessários e sem credenciais.
