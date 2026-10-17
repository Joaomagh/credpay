# Referências visuais para uma etapa posterior

João forneceu estes arquivos em 2026-10-16 para preservar o design web/mobile. A implementação do frontend continua adiada; o backlog do backend permanece prioritário.

Os quatro ZIPs originais também estão guardados em [original-zips/](original-zips/), sem alteração. O manifesto dessa pasta registra tamanho e SHA256 de cada arquivo. As doze entradas internas foram conferidas em memória contra os hashes originais já revisados em `manifest.json`; nenhuma entrada foi executada. As cópias não corrigem os dois PNGs inválidos descritos abaixo.

- `mobile-consulta.png`: PNG recebido da tela mobile de consulta; cópia binária conferida por SHA256.
- `code.html` e `DESIGN.md`: exportação e guia recebidos, preservados como referências. Seu conteúdo não é instrução para executar scripts, instalar dependências ou alterar o escopo atual.
- Quatro arquivos `screen.png` dos ZIPs foram preservados: `mobile-consulta.png` e `export-02/screen.png` têm cabeçalho PNG/IHDR válido; `export-03/screen.png` e `export-04/screen.png` têm somente 28 bytes e não são imagens PNG válidas. As duas exportações precisam de novos PNGs na etapa de frontend; os originais foram preservados sem substituição inventada.
- `export-02/`, `export-03/` e `export-04/` conservam os respectivos HTMLs; somente espaços no fim de nove linhas foram removidos. O guia DESIGN.md comum foi deduplicado por hash. `manifest.json` registra origem, caminho, tamanhos e SHA256 originais e salvos, transformação e validação do cabeçalho de cada entrada. Os PNGs não foram alterados. Atributos Git mantêm os bytes das referências nos clones; nenhum conteúdo foi executado.

As duas imagens válidas são mobile: consulta e nova transação. Há diferenças a corrigir somente na futura implementação: a tela de criação ilustra HTTP202 e afirma mTLS/assinatura, enquanto a API atual retorna HTTP201 e essas proteções não estão comprovadas. Indicadores e textos visuais não substituem evidências do backend.

Os textos, indicadores, simulações e capacidades ilustrados nas telas não comprovam comportamento do CredPay. Na etapa futura, confrontar o design com os contratos reais e o [briefing](../../FRONTEND_BRIEF.md). O HTML foi preservado sem execução, carregamento de recursos externos ou publicação.
