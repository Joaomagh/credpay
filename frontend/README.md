# CredPay — Console Angular

O console tem as telas **Nova transação** e **Consultar**, com criação por POST real e acompanhamento por GET. A tela **Entrar** exercita a autenticação do perfil local de contas. O ambiente usa dados fictícios.

## Executar no PowerShell

Na raiz do CredPay, com Node24.22.1 e npm10.9.2 ou superior:

```powershell
Set-Location frontend
npm.cmd ci --ignore-scripts --no-fund
npm.cmd start
```

Abra http://127.0.0.1:4200. Para parar o servidor frontend, use Ctrl+C. Nenhuma CLI global é necessária.

Para criar e consultar dados no modo padrão, prepare e ative o backend conforme o [roteiro Compose](../infra/compose/README.md). O proxy de desenvolvimento encaminha `/transacoes` e `/auth/**` para `http://127.0.0.1:8080`, na mesma origem do console e em loopback. Se a porta da API foi alterada, ajuste `proxy.conf.json`. O proxy é configuração de desenvolvimento. Hospedar o bundle exige configuração própria de origem e fallback das rotas.

## Entrada no perfil de contas

Abra `/entrar` com o serviço de transações em `sessao-demo` e uma conta fictícia já provisionada. A tela solicita o token CSRF, envia as credenciais em formulário e confirma a sessão no servidor. Recarregar consulta a sessão atual. Sair usa o token da sessão confirmada. A integração da sessão com as telas de criação, consulta e recuperação de recibos está no [backlog](../docs/BACKLOG.md#contas-individuais-e-segurança-da-demo-pública).

## Teste manual no navegador

Use a skill [credpay-frontend](../plugins/credpay-frontend-qa/skills/credpay-frontend/SKILL.md) para repetir a revisão de criação, consulta, teclado, responsividade e recuperação de intenção. No desktop, o plugin **credpay-frontend-qa** está no [catálogo do projeto](../.agents/plugins/marketplace.json). Reabra o aplicativo para carregar o catálogo CredPay, escolha o plugin no diretório e use a skill em uma nova sessão com o navegador integrado. O pacote fornece o roteiro e usa o controle de navegador disponível na sessão. [Documentação de plugins locais](https://developers.openai.com/plugins/build/plugins#install-a-local-plugin-manually)

## Jornada

1. Em **Nova transação**, informe um valor positivo em BRL, com vírgula ou ponto decimal e sem separador de milhar. Não limitamos a duas casas: a API trabalha com decimal exato.
2. Ao enviar, a intenção captura valor, moeda e uma chave UUID. O formulário trava o valor; cliques repetidos durante o envio não criam novo POST. Em falha/timeout, **Tentar com a mesma intenção** reaproveita chave e payload. HTTP400/422 e conflito409 são explicados separadamente.
3. Ao receber o pedido, copie o ID ou clique **Acompanhar transação**. POST confirma recebimento/PENDENTE inclusive em replay; GET revela o estado atual.
4. A consulta se repete dois segundos depois de cada resposta PENDENTE. Para no estado final, no erro, ao navegar/trocar ID, em30 respostas ou60segundos. Cada chamada tem timeout de8segundos. Limite/erro não transforma PENDENTE em FALHOU.

**Retomar após recarregar:** antes do POST, a intenção é guardada no sessionStorage da origem/aba. Recarregar recupera chave, payload e recibo anterior, se já recebido; não envia pedidos automaticamente. O recibo recuperado confirma somente o POST anterior (PENDENTE); use **Acompanhar transação** para consultar o estado atual. Em falha de comunicação sem recibo, **Tentar com a mesma intenção** mantém chave/payload.

Se o registro estiver inválido ou o armazenamento indisponível/sem espaço, a criação fica bloqueada em vez de enviar com outra chave. Repare a permissão/espaço e recarregue para tentar recuperar o registro existente. Registro ilegível permanece até **Iniciar nova intenção**, ação explícita que remove somente o registro CredPay e pode criar outra transação. Falha ao salvar o recibo mantém o ID confirmado visível; copie-o antes de fechar.

**Limites:** sessionStorage não é backup nem autoridade financeira; pode ser perdido ao fechar/limpar a sessão, muda com a origem (localhost e127.0.0.1 são distintas) e pode ser copiado inicialmente ao duplicar uma aba. Não guardamos credenciais nem usamos localStorage. O registro versionado tem limite de65.536caracteres e dados fictícios. Dados acessíveis ao JavaScript da origem não são armazenamento seguro. **Iniciar nova intenção** não é retry; após resposta ambígua, consulte o pedido anterior quando possível antes de escolher essa ação.


## Verificar

```powershell
npm.cmd run check
npm.cmd test
npm.cmd run build
npm.cmd audit --audit-level=low
```

Testes Angular/Vitest usam a fronteira HTTP controlada, sem RabbitMQ/PostgreSQL e sem substituir o aceite da jornada com backend real. O CI repete instalação pelo lockfile, tipos, comportamento, build e auditoria no Linux. A revisão visual usa navegador desktop/mobile, validação inválida e navegação; sucesso financeiro no navegador com backend é um aceite separado.

Framework standalone, Router, Forms e HttpClient; estado assíncrono com signals/RxJS. `lossless-json` preserva o token decimal recebido/enviado sem converter dinheiro para `Number`; a formatação só desloca caracteres. CSS e ícones SVG são locais, sem fonte/CDN ou framework visual adicional. Baseline, justificativas e override de segurança do Vite estão em [docs/frontend/BASELINE.md](../docs/frontend/BASELINE.md).

## Teste manual no navegador

Use a skill [credpay-frontend](../plugins/credpay-frontend-qa/skills/credpay-frontend/SKILL.md) para repetir a revisão de criação, consulta, teclado, responsividade e recuperação de intenção. No desktop, o plugin **credpay-frontend-qa** está no [catálogo do projeto](../.agents/plugins/marketplace.json). Reabra o aplicativo para carregar o catálogo CredPay, escolha o plugin no diretório e use a skill em uma nova sessão com o navegador integrado. O pacote fornece o roteiro e usa o controle de navegador disponível na sessão. [Documentação de plugins locais](https://developers.openai.com/plugins/build/plugins#install-a-local-plugin-manually)

## Jornada automatizada no CI

Compose CI executa seis cenários de navegador Chromium (desktop e celular) contra os dois aplicativos reais após Smoke: aprovação com decimal/HTTP201/Location, reload com mesmo recibo e sem outro POST, rejeição e validação/404. Não há mock de API nesses cenários. Banco/broker/apps pertencem somente ao projeto descartável credpay-ci; cleanup always permanece limitado a ele. O teste não controla o Docker do seu computador nem substitui todos os testes Java/concorrência/reentrega.

Verificações estruturais sem abrir navegador:

```powershell
npm.cmd run check:e2e
npm.cmd run e2e:list
```

A execução e2e:ci exige GitHubActions/CI e nome do projeto de teste; esse guard previne acidentes, não é isolamento. O CI baixa somente o Chromium headless compatível para seu temporário, sem instalar navegador global. Sem testes ignorados, sem retry automático e com deadlines; reporte público limita-se a nomes estáticos de cenários/etapas e contagens. Detalhes de erros, corpos, UUIDs, traces, vídeos e screenshots não são publicados por esse reporter. Em falha, identificar cenário/etapa e reproduzir o menor problema; não reduzir testes para passar. Mobile aqui é Chromium responsivo, sem prova de Safari/WebKit.
