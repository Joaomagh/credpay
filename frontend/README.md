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

**Limite da intenção:** fica apenas na memória desta aplicação, inclusive ao alternar suas duas telas. Recarregar/fechar a aba perde chave e resultado. Após resposta ambígua, guarde o ID quando disponível e consulte antes de iniciar outro pedido. **Iniciar nova intenção** é uma ação explícita e pode criar outra transação; não é retry da anterior. Não há armazenamento de dados no navegador nem recuperação de intenção após reload nesta versão.

## Verificar

```powershell
npm.cmd run check
npm.cmd test
npm.cmd run build
npm.cmd audit --audit-level=low
```

Testes Angular/Vitest usam a fronteira HTTP controlada, sem RabbitMQ/PostgreSQL e sem substituir o aceite da jornada com backend real. O CI repete instalação pelo lockfile, tipos, comportamento, build e auditoria no Linux. A revisão visual usa navegador desktop/mobile, validação inválida e navegação; sucesso financeiro no navegador com backend é um aceite separado.

Framework standalone, Router, Forms e HttpClient; estado assíncrono com signals/RxJS. `lossless-json` preserva o token decimal recebido/enviado sem converter dinheiro para `Number`; a formatação só desloca caracteres. CSS e ícones SVG são locais, sem fonte/CDN ou framework visual adicional. Baseline, justificativas e override de segurança do Vite estão em [docs/frontend/BASELINE.md](../docs/frontend/BASELINE.md).
