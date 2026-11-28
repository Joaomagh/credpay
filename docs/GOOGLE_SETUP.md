# Preparar o login Google

O código foi integrado na PR250. Em27/11, João criou o projeto e o cliente web `CredPay — Local` no console Google e confirmou no próprio PowerShell que ID, segredo e retorno estavam carregados. Os valores não foram solicitados para inspeção nem versionados. Primeiro acesso Google real local concluído; limites no estado de aceite abaixo. A documentação oficial exige projeto, cliente OAuth e URI de retorno cadastrada: [Google OpenID Connect](https://developers.google.com/identity/openid-connect/openid-connect).

## Configuração pessoal de João

1. Acessar o Google Cloud Console com a conta que será proprietária do projeto e criar/selecionar o projeto CredPay. Aceites de termos e credenciais são etapas pessoais; não enviar valores no chat.
2. Configurar a apresentação/consentimento do aplicativo com nome CredPay, contato de suporte real e público adequado. Usar somente autenticação `openid`; não solicitar Drive, contatos ou calendário. Manter o ensaio limitado a contas de teste enquanto a liberação externa não estiver validada no console.
3. Criar cliente OAuth para aplicação web. No primeiro ensaio local cadastrar exatamente `http://127.0.0.1:4200/auth/retorno/google`. `localhost`, `127.0.0.1`, portas e caminhos não são intercambiáveis.
4. Guardar client ID e secret somente na configuração privada do operador. As variáveis usadas pelo backend são `CREDPAY_GOOGLE_CLIENT_ID`, `CREDPAY_GOOGLE_CLIENT_SECRET` e `CREDPAY_GOOGLE_REDIRECT_URI`. Não colocar segredo no Angular, Wrangler vars, README, comandos publicados, screenshots ou Git.

O backend usa o perfil `google-login` e ativa a cadeia de sessão. Com o perfil desligado, o login por senha continua funcionando e `/auth/metodos` informa Google desativado. Configuração ausente/inválida com o perfil ligado aborta o startup.

## Ensaio local com infraestrutura própria

Preparar antes os JARs/imagens e o ambiente conforme [Compose](../infra/compose/README.md). O operador pode somar `-f infra/compose/compose.google-local.yaml` ao arquivo Compose base, com as variáveis privadas carregadas no ambiente, para ativar Google somente no serviço de transações. O overlay não instala ferramentas, cria contas ou altera volumes. O agente não executa Docker local conforme AGENTS.md.

Iniciar o Angular com `npm.cmd start -- --configuration contas`, dentro de frontend. Abrir `http://127.0.0.1:4200/entrar`. Confirmar `/auth/metodos` com `google=true`, entrar e cadastrar um cliente fictício. Sair e entrar com a mesma identidade deve preservar o cliente; outra identidade deve começar vazia e não acessar o UUID alheio. Verificar saída, recusa de CSRF ausente e persistência após reinício. Não publicar tokens, cookies ou respostas de autorização nas evidências.

Esse overlay usa HTTP somente em loopback no primeiro ensaio. Não é configuração de hospedagem. Não executar `docker compose config` com saída completa quando variáveis reais estiverem carregadas, pois a interpolação pode mostrar o segredo; validação de configuração deve usar modo quiet.

## URL pública sem domínio comprado

João confirmou que não tem domínio e quer custo zero. O frontend pode usar o subdomínio fornecido por Workers, adequado ao caráter de portfólio da demo: [workers.dev](https://developers.cloudflare.com/workers/configuration/routing/workers-dev/). A Cloudflare o destina a projetos pessoais/hobby, sem tratá-lo como garantia de operação crítica. O template atual mantém `workers_dev=false` até destino/configuração/aceites estarem prontos; não foi publicado.

O retorno Google público será `https://ENDERECO_PUBLICO_CONFIRMADO/auth/retorno/google`, cadastrado exatamente no cliente e no backend. A API também precisa de origem DNS/HTTPS confiável para o Worker. A opção gratuita para essa origem ainda precisa ser definida e ensaiada; o subdomínio do frontend não resolve sozinho o endereço da VM. Não usar certificado sem validação, túnel temporário como disponibilidade comprovada ou HTTP público para contornar essa etapa.

## Estado de aceite

Os testes automatizados usam emissor fictício, assinatura RSA/JWKS, PKCE, JWT e HTTP reais no ensaio, com persistência PostgreSQL no CI. Em27/11, João também concluiu o primeiro login com Google real no ambiente local: containers saudáveis, `/auth/metodos` com Google ativo e interface exibindo sessão ativa após o fluxo. Antes houve `invalid_client`; após recarregar o ID no terminal e recriar somente o backend, o acesso funcionou. A causa exata do valor anterior não foi inspecionada. João confirmou que o cliente permaneceu após sair e entrar com a mesma conta. Ainda faltam isolamento com segunda identidade, reinício e ensaio no destino público. Não publicar cookies, códigos de retorno, segredo ou identificadores da conta nas evidências.
