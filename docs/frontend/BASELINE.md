# Preparação do frontend Angular

Estado em22/10/2026: baseline registrada antes de instalar dependências. João reiterou interesse no frontend e completou a primeira demo Compose do backend. Baseline inicialmente registrada antes de qualquer download; a implementação solicitada por João está agora em frontend/, com verificações locais registradas em spec9.127. Não representa fechamento da v1 do backend.

## Necessidade concreta

O recorte de [FRONTEND_BRIEF.md](../FRONTEND_BRIEF.md) é criar e consultar transações reais em duas telas responsivas. As referências Stitch permanecem em references/. Não introduzir APIs ou dados inventados para preencher a interface.

João informou inicialmente Node22.11.0 e npm10.9.2. Após instalar manualmente pelo WinGet em22/10, confirmou no PowerShell node --version com v24.22.1 e npm.cmd --version com10.9.2. Ambos atendem aos requisitos registrados para Angular22/CLI. Evidência fornecida por João, sem execução do instalador pelo agente. CLI global não é necessária: ferramentas do frontend serão locais ao projeto, com versões e lockfile. Na preparação inicial nenhuma biblioteca Angular foi baixada; a resolução posterior está fixada no lockfile de frontend/.

## Runtime selecionado

- Node.js24.22.1 LTS/Krypton, atualização de segurança publicada pelo projeto. Metadados oficiais indicam npm11.19.1 incluído; o terminal de João continuou encontrando npm10.9.2 após a atualização. A origem dessa resolução não foi investigada; não é necessário alterar o npm global apenas para igualar a versão incluída no instalador.
- Compatibilidade Angular22: Node^22.22.3, ^24.15.0 ou >=26; TypeScript>=6.0.0<6.1 e RxJS^6.5.3 ou ^7.4.0. Selecionar a linha Node24 mantém uma baseline LTS compatível; não usar22.11 apenas por estar instalada.
- Instalação manual no Windows: pacote WinGet OpenJS.NodeJS.LTS, versão24.22.1, origem winget, instalador wix. Isso atualiza o Node instalado no computador; o agente não executará instalador global.
- Origem oficial x64: https://nodejs.org/dist/v24.22.1/node-v24.22.1-x64.msi; SHA256 `3bda4baf1ee709b33cf920dd633106ddb23951e322bff9c03ec9f64ab8981b01`.
- Se necessário ao build do agente, runtime portátil limitado ao workspace: node-v24.22.1-win-x64.zip; SHA256 `d2046d28c2c3dcd211ac5145c0bf30e2047ef9dfb306693c8c6e2eccd8b95fa5`. Tamanho, caminhos/tipos e hash devem ser verificados antes da extração; o download posterior foi conferido por tamanho/SHA/caminhos e extraído somente em .local/frontend.

Fontes consultadas: [compatibilidade Angular](https://angular.dev/reference/versions), [release Node24.22.1](https://nodejs.org/en/blog/release/v24.22.1), [metadados Node](https://nodejs.org/dist/index.json), [checksums oficiais](https://nodejs.org/dist/v24.22.1/SHASUMS256.txt) e [manifesto WinGet](https://github.com/microsoft/winget-pkgs/blob/master/manifests/o/OpenJS/NodeJS/LTS/24.22.1/OpenJS.NodeJS.LTS.installer.yaml). O SHA foi conciliado entre Node e WinGet; assinatura GPG não foi verificada. Versões fixadas são um corte, não promessa de ausência de vulnerabilidades futuras.

## Ferramentas selecionadas e resolução

Implementação solicitada em22/10: Angular standalone22.2.3 (core/common/compiler/forms/platform-browser/router/compiler-cli), CLI/build22.2.4, TypeScript6.0.3, RxJS7.8.2 e tslib2.8.1. Vitest5.0.5/jsdom30.1.3 exercitam comportamento Angular/HTTP sem servidor Docker; lossless-json4.3.3 preserva números JSON sem converter BigDecimal em ponto flutuante JavaScript. Não adicionamos biblioteca visual: CSS local aplica as referências Stitch. Licenças, engines, peers e integridades publicadas constam em dependencies-baseline.json, consultadas antes da resolução. O lockfile registra também transitivas; auditoria npm e build/testes locais passaram antes da revisão, com repetição Linux comprovada no CI Frontend Angular1/run39879369889 (19testes, tipos, build e audit0). Scripts de instalação ficam desabilitados inicialmente.

A primeira resolução trouxe Vite8.3.0 fixado por @angular/build e npm audit reportou uma ocorrência alta e uma baixa (incluindo propagação ao build). Não houve aceite: override explícito Vite8.3.5, patch da mesma linha, com metadados/integridade consultados antes da atualização. Build/testes e dev server locais passaram com o override; a nova auditoria reportou zero vulnerabilidades. O CI Linux repetiu as verificações com sucesso em Frontend Angular1/run39879369889; gates do SHA final serão conferidos antes da integração. Não usar audit fix --force ou reduzir o scanner.

Runtime do agente: ZIP oficial Node24.22.1 com37.748.064bytes conforme HEAD, limitado a .local/frontend. Validar tamanho/SHA e caminhos antes de extrair; nenhuma instalação global. Essa execução local não comprova sandbox/egress restrito; limitações documentadas continuam aplicáveis.

CI Linux usa a mesma versão Node24.22.1 oficial: node-v24.22.1-linux-x64.tar.xz, SHA256 `6c172a9ab6718f7b801fb5a7bd486af2ee5e32758086a04b84fcce2aa77095e6`, consultado em SHASUMS256 antes de criar o workflow. Checkout reutiliza actions/checkout v7.0.1 fixado no repositório; runtime/cache/configs npm ficam no temporário do runner, sem credencial persistida. Nenhum serviço externo novo ou deploy.

Metadados publicados no registro npm consultados em22/10: @angular/core22.2.3 e @angular/cli22.2.4, ambos exigindo as linhas Node acima. A CLI admite npm>=8. As versões fixadas acima e o lockfile identificam a resolução do primeiro incremento executável; não usar tags latest como identidade persistente.

Não instalar CLI global, contratar serviço, publicar site ou alterar CORS do backend nesta preparação. Os comportamentos implementados seguiram red/green e preservação decimal/idempotência do briefing; a jornada manual no navegador com backend ativo permanece pendente. Baseline de ferramentas não comprova contenção do agente; restrições de trabalho e limites da sandbox continuam documentados separadamente.

Inspeção responsiva no navegador: viewports320/390/1280, sem overflow horizontal nos recortes conferidos; foco visível e validação negativa observados. Não constitui auditoria completa WCAG nem jornada de sucesso com backend ativo. Capturas locais em .local/frontend, sem substituir as referências originais.
