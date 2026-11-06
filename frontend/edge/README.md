# Entrada pública candidata

Preparação de código, **sem deploy ou URL pública**. A publicação continua bloqueada pelo HIGH APT e pelos aceites do [plano público](../../docs/PUBLIC_DEMO_PLAN.md). Nenhuma conta, DNS, certificado ou serviço foi criado.

O módulo `worker.ts` atende `/`, `/entrar`, `/nova` e `/consultar` pelo binding de assets do Angular. Permite somente GET de `/auth/csrf`, `/auth/sessao` e `/transacoes/{UUID}`, e POST de `/auth/login`, `/auth/logout` e `/transacoes`. Métodos divergentes devolvem405; query na API devolve400. Rotas administrativas, arquivos privados e mapas de fontes não são encaminhados. Assets aceitam somente as extensões declaradas no módulo, dentro do bundle.

`API_ORIGIN` exige uma origem HTTPS/DNS configurada pelo responsável, sem credenciais, caminho, query, fragmento ou porta alternativa. Valor ausente/inválido ou a própria origem do console devolvem503 sem fetch. Isso valida sintaxe e configuração; **não comprova propriedade do DNS, isolamento de rede ou allowlist de egress**. O destino ainda precisa de revisão, TLS válido e proteção contra acesso direto indevido.

POST exige `Origin` exatamente igual à origem pública; a API recusa `Sec-Fetch-Site: cross-site`. Isso complementa autenticação e CSRF do servidor. Encaminhamos somente `JSESSIONID`, chave idempotente, CSRF, conta esperada e cabeçalhos de conteúdo. Authorization e cabeçalhos de encaminhamento do cliente são descartados. Dinheiro atravessa como bytes, sem conversão numérica.

Cada operação de API tem orçamento total de7segundos, incluindo leitura da entrada, fetch e resposta, com cancelamento/HTTP504. Entrada de8KiB e resposta de32KiB são limites de bytes, também sem Content-Length. Não aceitamos entrada comprimida; pedimos resposta identity e recusamos outra codificação. Redirects são recusados sem seguir Location. Fetch usa `cache: no-store`; respostas da API usam `Cache-Control: no-store`. Falhas têm mensagem fixa, sem detalhes de infraestrutura ou logs de cabeçalhos/corpos.

O servidor precisa emitir `JSESSIONID` host-only com `Secure`, `HttpOnly`, `Path=/` e `SameSite=Lax`, inclusive na exclusão. Cookies divergentes são recusados, sem reescrita. O perfil HTTP local padrão **não é a configuração pública**: a origem precisa de HTTPS, `server.servlet.session.cookie.secure=true`, contas próprias e `sessao-demo` ativo. Expiração temporal, retenção, limites compartilhados e operação no destino continuam pendentes.

## Conferir sem publicar

Na pasta `frontend`, com o runtime já fixado:

```powershell
npm.cmd run check:edge
npm.cmd run test:edge
npm.cmd run build -- --configuration production,contas
```

Os testes usam Request/Response/Streams reais do Node24, com fetch e assets controlados na fronteira. Não acessam rede, Docker ou contas. A cobertura V8 real em `coverage/edge/lcov.info` é validada/projetada para o Sonar preservando os hits. Isso não executa Workers, não comprova certificado/cookie no navegador e não substitui smoke externo ou revisão manual com backend real.

`wrangler.example.jsonc` é um modelo revisável, sem instalação de Wrangler ou comando de deploy. Aponta exclusivamente para `dist/console/browser`, mantém API_ORIGIN vazia e URLs de preview/workers.dev desabilitadas. `run_worker_first: true` impede que navegação transforme API em HTML; somente as quatro rotas do console recebem o shell. Toda requisição, inclusive asset, invoca o Worker e participa da franquia. Confirmar limites gratuitos e medir CPU no runtime antes da adoção. Não copiar `.env`, `.local`, checkout ou mapas de fontes para assets.

Fontes oficiais consultadas06/11: [roteamento](https://developers.cloudflare.com/workers/static-assets/routing/worker-script/), [SPA/navegação](https://developers.cloudflare.com/workers/static-assets/routing/single-page-application/), [fetch/cache](https://developers.cloudflare.com/workers/runtime-apis/fetch/), [Set-Cookie](https://developers.cloudflare.com/workers/runtime-apis/headers/).
