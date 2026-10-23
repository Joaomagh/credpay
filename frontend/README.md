# CredPay — Console Angular

Duas telas responsivas a partir das referências Stitch: **Nova transação** e **Consultar**. Criação por POST real e acompanhamento por GET; não há servidor de respostas simuladas, autenticação, extrato ou pagamentos reais.

## Executar no PowerShell

Na raiz do CredPay, com Node24.22.1 e npm10.9.2 ou superior:

```powershell
Set-Location frontend
npm.cmd ci --ignore-scripts --no-fund
npm.cmd start
```

Abra http://127.0.0.1:4200. Para parar o servidor frontend, use Ctrl+C. Nenhuma CLI global é necessária.

Para criar/consultar dados, o backend precisa estar preparado e ativado conforme [roteiro Compose](../infra/compose/README.md). O frontend usa proxy de desenvolvimento somente para `/transacoes`, direcionado a `http://127.0.0.1:8080`; sem abrir CORS ou expor a aplicação na rede. Se a porta da API foi alterada, ajuste explicitamente `proxy.conf.json`. A página abre sem backend, mas os pedidos retornarão erro de comunicação. O proxy é configuração de desenvolvimento; hospedar o bundle de produção exige configuração própria de origem e fallback das rotas, fora desta entrega.

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

## Jornada automatizada no CI

Compose CI executa seis cenários de navegador Chromium (desktop e celular) contra os dois aplicativos reais após Smoke: aprovação com decimal/HTTP201/Location, reload com mesmo recibo e sem outro POST, rejeição e validação/404. Não há mock de API nesses cenários. Banco/broker/apps pertencem somente ao projeto descartável credpay-ci; cleanup always permanece limitado a ele. O teste não controla o Docker do seu computador nem substitui todos os testes Java/concorrência/reentrega.

Verificações estruturais sem abrir navegador:

```powershell
npm.cmd run check:e2e
npm.cmd run e2e:list
```

A execução e2e:ci exige GitHubActions/CI e nome do projeto de teste; esse guard previne acidentes, não é isolamento. O CI baixa somente o Chromium headless compatível para seu temporário, sem instalar navegador global. Sem testes ignorados, sem retry automático e com deadlines; reporte público limita-se a nomes estáticos de cenários/etapas e contagens. Detalhes de erros, corpos, UUIDs, traces, vídeos e screenshots não são publicados por esse reporter. Em falha, identificar cenário/etapa e reproduzir o menor problema; não reduzir testes para passar. Mobile aqui é Chromium responsivo, sem prova de Safari/WebKit.
