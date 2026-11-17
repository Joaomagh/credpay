# Fechamento do MVP CredPay — roteiro executável

Atualizado em 17/11/2026. Este roteiro organiza os aceites existentes; não declara uma release nem autoriza implantação externa. Referências: [backlog](BACKLOG.md), [candidato](V1_CANDIDATE.md), [validação](V1_VALIDATION.md), [demo](DEMO.md), [triagem](../infra/security/TRIAGE.md) e [plano público](PUBLIC_DEMO_PLAN.md).

**Bloqueio observado hoje:** os dois jobs nativos da candidata PostgreSQL passaram no head `6a05c55`, mas o job Sonar parou antes do scanner porque o `npm audit` completo do frontend passou a encontrar HIGH em dependências de desenvolvimento. O run CI contou 16 ocorrências; consulta local ao mesmo lockfile contou 14, diferença temporal do feed que não deve ser escondida. Ambas apontam a [GHSA-fc5q-39xc-vjpf](https://github.com/advisories/GHSA-fc5q-39xc-vjpf) de `picomatch` até 4.0.7, sem versão corrigida publicada na consulta de 17/11. O audit local `--omit=dev` contou zero apenas no recorte de produção; **não substitui o gate completo**. A PR #244 permanece sem merge até uma correção de fornecedor ou alternativa tecnicamente revisada passar o mesmo gate. Não executar `npm audit fix --force` nem reduzir o escopo da auditoria para fazê-lo passar.

## Dois resultados distintos

| Marco | Definição de pronto | Estado |
| --- | --- | --- |
| **MVP local / v1** | SHA final reproduzível, checks aplicáveis verdes, achados e limites registrados, fluxo com contas demonstrado e aceite pessoal de João. | Fluxo técnico demonstrado; corte final e aceite pessoal pendentes. |
| **MVP público** | A jornada abre em URL HTTPS para visitantes, com backend real, contas, limites, retenção, diagnóstico e recuperação testados no destino gratuito. | Sem implantação; banco e operação pública ainda bloqueiam. |

A v1 termina por critérios comprovados, não por percentual ou número de PRs. O plano inicial separava frontend do aceite do backend; João retomou o Angular e pediu login depois. Este roteiro fecha o produto atual sem reclassificar os marcos históricos. Painel Prometheus/Grafana, sandbox do agente, pagamentos reais, alta disponibilidade de produção e Jenkins não são aceites desta entrega.

## A. Fechar o MVP local

### 1. Concluir a candidata PostgreSQL isolada

**Responsável:** engenharia. [PR #244](https://github.com/Joaomagh/credpay/pull/244) avalia PostgreSQL 17.12 Alpine com `su-exec`, sem trocar o banco ativo ou tocar nos volumes locais.

1. Conferir os jobs nativos AMD64 e ARM64 no mesmo head. O probe deve provar `USER postgres` por padrão, UID 70, criação e consulta SQL, linha persistida após reinício em volume descartável próprio e queda de privilégio no fallback iniciado explicitamente como root.
2. Ler os dois relatórios selecionados: identidade e arquitetura da imagem, Alpine e cobertura OS, versão de `su-exec`, cada ID e severidade. O binário Go antigo precisa estar ausente do TAR distribuível, não apenas do filesystem em execução.
3. Exigir Secret Scan, inventários gerais e SonarQube verdes. O gate pede rating A de segurança no código novo. Revisar alertas por arquivo/linha; corrigir ou justificar individualmente com evidência, sem desligar regra ou afrouxar gate.
4. Integrar somente após checks e revisão resolvidos. Registrar SHA de merge, runs e limites. **Saída:** experimento integrado; imagem ativa e volumes preservados. Scan zero em um feed não equivale a promoção.

### 2. Aprovar a imagem de banco que será realmente usada

**Responsável:** engenharia. João escolheu migrar para uma plataforma/imagem corrigida, com baseline revisada e testes completos antes de fechar a v1. O inventário de 16/11 registrou 4 CRITICAL/135 HIGH na imagem Bookworm ativa e 1 CRITICAL/44 HIGH na candidata Alpine anterior. A candidata nova teve resultado melhor em um run, mas ainda requer integração e restauração.

1. Em incremento separado, escrever testes para migrations dos dois serviços, constraints, persistência, reinício, backup lógico/restauração, fluxo RabbitMQ e recuperação. Confirmar red pelo motivo esperado antes da troca.
2. Executar em AMD64 e ARM64, com versões/digests fixos. Repetir inventários de banco, broker, JARs e imagens dos apps; comparar IDs e cobertura, não só totais. Tratar CRITICAL/HIGH e registrar demais achados sem aceite implícito.
3. Restaurar dados de teste em **volume novo**, nunca montar um volume Bookworm existente diretamente na imagem Alpine. Provar Compose, Kubernetes autorizado e rollback antes de mudar o pin ativo. Não apagar volumes para contornar falhas.
4. Atualizar triagem, quickstart e identidade da imagem. **Saída:** banco da demo com baseline aprovada e recuperação comprovada; caso contrário, URL pública continua bloqueada.

### 3. Fechar os achados de qualidade

**Responsável:** engenharia. A [PR #243](https://github.com/Joaomagh/credpay/pull/243) passou o gate, mas o Sonar mostrou seis riscos novos de dependência. Os seis IDs `undici` foram triados em [SONARQUBE_CI.md](SONARQUBE_CI.md); o lockfile corrigido passou nos testes locais, mas aguarda CI e confirmação de cada ID no Sonar.

1. Registrar os seis IDs, pacote/versão, severidade, caminho de entrada e condição de exploração com fonte primária. Conferir código e lockfile; `npm audit` zero não encerra o Sonar.
2. Para cada ID, corrigir e testar ou registrar disposição individual sustentada. Não encerrar por contagem agregada ou supressão ampla.
3. Reexecutar as suítes e jornadas afetadas. Exigir gate A, Secret Scan e inventários aplicáveis no SHA final. **Saída:** seis decisões rastreáveis e nenhum bloqueador sem disposição válida.

**Novo primeiro item desta etapa:** identificar a versão corrigida de `picomatch` quando existir, confirmar integridade/proveniência e compatibilidade com Angular/Vitest, atualizar lockfile e repetir `npm ci`, tipos, testes, build, browser e `npm audit` completo. Se não houver versão publicada, manter o bloqueio explícito; uma contagem menor com `--omit=dev` é informação de alcance, não aceite de segurança.

### 4. Congelar o candidato técnico

**Responsável:** engenharia. Após as correções, obter `git rev-parse HEAD` em `main` e registrar o SHA completo em [V1_CANDIDATE.md](V1_CANDIDATE.md). O corte histórico `51f8476` não representa alterações posteriores.

| Prova aplicável ao corte | Resultado exigido |
| --- | --- |
| Transações e Processamento | `verify` completo, migrations, constraints, integrações e cenários de falha sem falhas/skips inesperados. |
| Flow, Images, Compose e Kubernetes autorizado | APROVADA/REJEITADA, idempotência, PENDENTE durante parada, recuperação causal, persistência e cleanup próprios. |
| Frontend e navegador | Login/logout, criação/consulta, isolamento entre contas, expiração, erros e desktop/mobile. |
| SonarQube, Secret Scan e inventário AMD64/ARM64 | Gates verdes e relatórios vinculados ao SHA, com limitações explícitas. |

Conferir `git diff --check`, README/quickstart/contratos, revisões pendentes e os seis conteúdos históricos marcados **NÃO VERIFICADOS**. Reexecutar cenários afetados por mudanças; evidência anterior só vale com identidade de código demonstrada. **Saída:** um candidato final verificável, sem imagens de outra revisão.

### 5. Ensaio pessoal e decisão de release

**Responsável:** João, com acompanhamento. Compose, Kubernetes e métricas passaram em ensaios assistidos; a explicação autônoma foi adiada e permanece pendente.

1. Na raiz do checkout final, no PowerShell 7, conferir `$PSVersionTable.PSVersion.ToString()`, `java -version`, `docker compose version`, `docker version --format '{{.Server.Os}}'` e `git rev-parse HEAD`. O engine precisa responder `linux`. Não compartilhar `.env`, cookies, tokens ou logs integrais.
2. Seguir **Construir e preparar** do [quickstart](../infra/compose/README.md), preservando `.env` existente. A tag das imagens deve corresponder ao SHA dos JARs construídos. `package -DskipTests` prepara a demo; o CI executa as suítes.
3. Executar separadamente `./infra/compose/demo.ps1 -Action Prepare -Diagnostics`, `./infra/compose/demo.ps1 -Action Activate -Diagnostics` e `./infra/compose/demo.ps1 -Action Demo -Diagnostics`, conferindo cada resultado antes de avançar. Esperado: health/metrics, topologia e consumidores, APROVADA/REJEITADA e replay estável.
4. Demonstrar parada e recuperação pelo [roteiro](DEMO.md): mesmo UUID e intenção preservados enquanto o processador está ausente. Relacionar métricas de tentativas com estado, outbox e fila; tentativas não são transações únicas. Ao terminar, `./infra/compose/demo.ps1 -Action Down` preserva volumes.
5. João explica com suas palavras por que POST devolve PENDENTE, onde RabbitMQ entra, quem decide, por que GET muda, como repetição/parada são tratadas e um limite real. Registrar data, SHA, comandos e dúvidas sem presumir entendimento.
6. Revisar [V1_VALIDATION.md](V1_VALIDATION.md) e decidir se aprova o candidato como CredPay v1. Só após aceite explícito registrar release/tag e atualizar a vitrine pública. **Saída:** decisão pessoal e técnica auditável.

## B. Habilitar a URL pública gratuita

### 6. Fechar o contrato operacional

**Responsáveis:** engenharia e João para escolhas de conta/escopo. Especificar contas individuais de visitantes, criação/revogação, dados somente fictícios, retenção física verificável, orçamento compartilhado de requisições e limites por conta/origem. Fazer TDD para cada comportamento novo. O perfil `public-demo` já exige HTTPS, cookie seguro, CSRF e sessão absoluta de até 15 minutos; repetir no destino. Management, métricas administrativas, bancos e broker ficam internos. **Saída:** isolamento, abuso limitado e expiração de dados comprovados.

### 7. Validar o destino e preparar publicação

**Responsável:** João escolhe/autoriza conta, região e destino; engenharia prepara configuração/rollback. O [plano público](PUBLIC_DEMO_PLAN.md) propõe Cloudflare Workers Free com Static Assets/proxy para Angular e Oracle Always Free A1 para Java, PostgreSQL e RabbitMQ. Reconferir condições atuais, capacidade regional, ARM64, cotas, ociosidade e teto de gasto zero antes de criar recursos. Se não houver capacidade, registrar impedimento e avaliar alternativa gratuita sem reduzir requisitos.

Preparar Compose ARM64, certificado confiável, HTTPS navegador→Worker **e** Worker→origem, binding privado de identidade da origem, apenas rotas `/auth/**` e `/transacoes/**`, fallback SPA e rollback. Segredos são configurados pelo titular fora do Git/chat. Contas, termos e implantação externa exigem suas decisões específicas. **Saída:** configuração/custo/rollback revisados antes do deploy.

### 8. Publicar e validar externamente

Após autorização específica, publicar o SHA aprovado. De fora do localhost, testar URL no desktop e celular, login individual, POST PENDENTE→GET final, APROVADA/REJEITADA, replay, 404 entre contas, logout/expiração, 429/limites, retenção, parada/recuperação, TLS nos dois trechos e orçamento gratuito. Registrar primeira resposta após ociosidade; free tier não garante 24/7. Provar rollback sem afetar outro projeto. **Saída:** URL no README, evidências, limitações e plano de desligamento.

## Registro mínimo por aceite

```text
Etapa / responsável:
Data e SHA completo:
Ambiente e imagem por digest:
Comando ou run (link):
Esperado / observado:
Gate, inventário e pendências:
Decisão / próxima ação única:
```

Se uma etapa falhar, registrar esperado, observado, hipótese, experimento mínimo e resultado. Não mascarar falha com outro check, mudar gate ou apagar volume. O backlog conserva histórico; `task.md` aponta a ação atual.
