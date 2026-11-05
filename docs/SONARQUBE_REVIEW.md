# CredPay - revisão inicial do SonarQube

> Relatório técnico de triagem, elaborado em 05/11/2026. Não é exportação oficial/certificação do fornecedor.

- Projeto público: `Joaomagh_credpay`; organização: `joaomagh`; branch: `main`.
- Análise: `432288d7-ac7e-437a-93b2-068fafe50292`, em `2026-11-05T11:22:36-03:00`.
- SHA analisado e inspecionado: `ccdd3e65e68b759e76b85ae821891485b9e61f1c`.
- Escopo: todos os 28 itens retornados pela consulta pública, com prioridades propostas; nenhum status alterado.
- PDF de leitura: [relatório](../output/pdf/credpay-sonarqube-2026-11-05.pdf).

## Leitura do resultado

A análise de main encontrou 28 apontamentos únicos. Desses, 14 têm impacto Security: 4 High e 10 Medium. Os outros 14 tratam de confiabilidade, manutenibilidade ou acessibilidade. Todos estavam OPEN no recorte coletado.

Os quatro High de segurança estão na pesquisa de sandbox, em comparações de configuração com /tmp. A inspeção indica provável falso positivo de contexto; nenhuma ocorrência foi encerrada. Essa pesquisa permanece incompleta e não liberada para uso.

Os dez Medium de segurança são seis downloads de ferramentas no CI sem restrição explícita de protocolos nos redirects, um fluxo de URL de download de pacotes e três credenciais fictícias usadas em Kubernetes descartável.

A análise atual não contabilizou Java na distribuição de linhas por linguagem e não tem cobertura importada. Logo, não sustenta uma conclusão sobre a segurança completa dos dois serviços Spring Boot. O quality gate aparece como Not computed.

Esta revisão não confirmou incidente, vazamento de credencial real ou exploração remota. Também não prova sua ausência. Os achados e as lacunas permanecem abertos até validação e decisão específica.

## Prioridade proposta

| Prioridade | Trabalho | Aceite |
| --- | --- | --- |
| P1 | Completar a análise | Conectar CI com classes Java dos dois serviços, separar fontes/testes e importar dois JaCoCo + LCOV. Conferir os avisos e a análise do mesmo SHA. |
| P1 | Reforçar seis downloads | S05-S10: restringir curl a HTTPS em URL e redirects, preservando versões, TLS e hashes. Testar o bloqueio de downgrade e os workflows afetados. |
| P1 | Reduzir custo no frontend | Q01-Q02: remover algoritmos de regex superlineares com TDD; manter dinheiro exato e recibo/idempotência. Custo local foi observado, sem DoS remoto demonstrado. |
| P1 externo | Segredos da publicação | S12-S14: impedir exemplos conhecidos no destino e provisionar credenciais exclusivas fora do Git. No ensaio descartável atual, a revisão contextual é P3. |
| P2 | Revisar a sandbox e o produto | S01-S04/S11: triagem por ID e testes negativos, sem liberar pesquisa. Q03/Q04/Q07: semântica da moeda, prova de cancelamento e nome de variável PowerShell. |
| P3 | Isolar fixtures e referências | Q05/Q06/Q08-Q14: escopo dos stubs, parser ELF e mockups históricos. Preservar originais e o inventário. Não editar referências para maquiar métricas. |

P1 significa próximo incremento ou condição antes de abrir a demo; P2 significa revisão/correção subsequente; P3 significa manutenção ou pesquisa sem exposição demonstrada no produto. Não foi confirmado P0 nesta inspeção. Prioridade proposta não altera a severidade do fornecedor nem aceita risco.

## Catálogo completo

A API fornece `severity` histórica e `impacts` por qualidade. No presente recorte, os quatro Security HIGH têm `severity=CRITICAL`; isso não são quatro ocorrências adicionais. Q04 é Blocker de manutenibilidade, não de segurança. Q13/Q14 são High de confiabilidade/manutenibilidade em referências de design.

### S01 - Diretório temporário: conjunto permitido do worker

- [Issue AaGsF8vpDE2kr22yY43g](https://sonarcloud.io/project/issues?id=Joaomagh_credpay&open=AaGsF8vpDE2kr22yY43g) | `python:S5443` | SECURITY:HIGH | legado `CRITICAL` | `OPEN`.
- Local: `infra/agent-sandbox/containment.py:138`. Prioridade proposta: **P2**.
- Avaliação: Provável falso positivo de contexto; revisão formal pendente.

A regra S5443 procura uso inseguro de diretórios graváveis por outros usuários. Nesta ocorrência, a expressão compara as chaves de HostConfig.Tmpfs com o conjunto permitido contendo /tmp. Não cria nem abre um arquivo temporário.

**Evidência e limite.** containment.py:138 verifica o destino e exige exatamente rw,noexec,nosuid,nodev,size=256m. O worker exige UID/GID10001, rootfs somente leitura, capabilities removidas e rede none neste perfil. Isso não comprova contenção completa nem libera a sandbox experimental.

**Ação.** Revisar esta ocorrência com os controles negativos e registrar a justificativa individual no SonarQube. Preservar as verificações; evitar renomear constantes apenas para esconder o alerta.

**Aceite.** Revisão reproduzível da expressão e dos mounts; rejeição de destino/opções divergentes; decisão explícita por ID antes de encerrar o achado.

### S02 - Diretório temporário: chave de consulta das opções

- [Issue AaGsF8vpDE2kr22yY43h](https://sonarcloud.io/project/issues?id=Joaomagh_credpay&open=AaGsF8vpDE2kr22yY43h) | `python:S5443` | SECURITY:HIGH | legado `CRITICAL` | `OPEN`.
- Local: `infra/agent-sandbox/containment.py:138`. Prioridade proposta: **P2**.
- Avaliação: Provável falso positivo de contexto; revisão formal pendente.

Segundo apontamento na mesma linha 138. O literal /tmp é a chave usada para consultar as opções do tmpfs inspecionado; não é um segundo arquivo vulnerável.

**Evidência e limite.** A comparação recusa opções diferentes da allowlist. A duplicidade de linha corresponde a duas posições distintas marcadas pelo analisador e foi preservada no inventário.

**Ação.** Revisar separadamente o acesso ao dicionário e a allowlist, mantendo a rastreabilidade entre S01 e S02.

**Aceite.** Confirmar que nenhuma operação de arquivo ocorre nesse ponto e manter teste que recuse opções extras ou ausentes. Sem supressão geral da regra.

### S03 - Diretório temporário: identificação do mount inspecionado

- [Issue AaGsF8vpDE2kr22yY43i](https://sonarcloud.io/project/issues?id=Joaomagh_credpay&open=AaGsF8vpDE2kr22yY43i) | `python:S5443` | SECURITY:HIGH | legado `CRITICAL` | `OPEN`.
- Local: `infra/agent-sandbox/containment.py:142`. Prioridade proposta: **P2**.
- Avaliação: Provável falso positivo de contexto; revisão formal pendente.

Na linha 142, o código identifica um mount cujo tipo é tmpfs e destino é /tmp. O literal serve à inspeção da configuração, sem criação de arquivo.

**Evidência e limite.** A verificação anterior exige um único destino tmpfs e suas opções. Os outros mounts passam pela validação de origem, destino, propagação e propriedade do bind da fixture.

**Ação.** Conferir a sequência completa dos guards e seus casos negativos antes de propor o encerramento por falso positivo.

**Aceite.** Mount extra, bind inesperado e opções divergentes continuam recusados; justificativa vinculada ao ID S03.

### S04 - Diretório temporário: validação do container de origem

- [Issue AaGsF8vnDE2kr22yY43f](https://sonarcloud.io/project/issues?id=Joaomagh_credpay&open=AaGsF8vnDE2kr22yY43f) | `python:S5443` | SECURITY:HIGH | legado `CRITICAL` | `OPEN`.
- Local: `infra/agent-sandbox/source_ci.py:32`. Prioridade proposta: **P2**.
- Avaliação: Provável falso positivo de contexto; revisão formal pendente.

source_ci.py:32 compara o tipo e o destino dos mounts observados. A operação sinalizada não cria arquivo em diretório público.

**Evidência e limite.** O controlador recusa mounts que não sejam tmpfs em /tmp, confere imagem/ID/label e exige que o container de inventário nunca tenha sido iniciado. Esse guard isolado não constitui prova completa de segurança da sandbox.

**Ação.** Revisar o uso como comparação e a cadeia de identidade/estado. Não promover a pesquisa de sandbox como resultado desta triagem.

**Aceite.** Testes de imagem/ID e mount indevido preservados; decisão de triagem específica. Nenhum aceite automático de vulnerabilidade.

### S05 - Download de Node no Compose CI permite redirecionamento sem restrição explícita

- [Issue AaGsF8v3DE2kr22yY43q](https://sonarcloud.io/project/issues?id=Joaomagh_credpay&open=AaGsF8v3DE2kr22yY43q) | `githubactions:S6506` | SECURITY:MEDIUM | legado `MAJOR` | `OPEN`.
- Local: `.github/workflows/credpay-compose-ci.yml:139`. Prioridade proposta: **P1**.
- Avaliação: Melhoria concreta de defesa do CI; exploração não demonstrada.

O curl parte de HTTPS e segue redirects com --location. Sem restringir os protocolos, o comando não declara que cada salto deve continuar em HTTPS.

**Evidência e limite.** credpay-compose-ci.yml:139 baixa Node24.22.1 de nodejs.org e confere SHA256 fixo antes da extração. O hash reduz o risco de aceitar conteúdo alterado, mas a restrição de transporte ainda pode ser explícita.

**Ação.** Adicionar --proto '=https' e --proto-redir '=https', preservando validação TLS, SHA256 e falha antes da extração.

**Aceite.** Download oficial funciona no runner, downgrade para HTTP é recusado e checksum continua obrigatório; repetir o check afetado e a análise.

### S06 - Download de Node no Frontend CI sem restrição explícita de redirects

- [Issue AaGsF8v0DE2kr22yY43n](https://sonarcloud.io/project/issues?id=Joaomagh_credpay&open=AaGsF8v0DE2kr22yY43n) | `githubactions:S6506` | SECURITY:MEDIUM | legado `MAJOR` | `OPEN`.
- Local: `.github/workflows/frontend-ci.yml:45`. Prioridade proposta: **P1**.
- Avaliação: Melhoria concreta de defesa do CI; exploração não demonstrada.

A mesma regra S6506 se aplica ao runtime que executa build, testes e auditoria Angular. HTTPS na URL inicial não expressa a política para todos os redirecionamentos.

**Evidência e limite.** frontend-ci.yml:45 usa Node24.22.1 oficial com SHA256 fixo e só extrai após a conferência. Não há indicação de download malicioso observado.

**Ação.** Restringir curl a HTTPS na requisição e nos redirects; manter versão, hash, isolamento de configurações npm e validação TLS.

**Aceite.** Frontend CI mantém tipos, 45 testes, build e audit; controle de downgrade negativo e nova análise do achado.

### S07 - Download de kind no Kubernetes CI sem restrição explícita de redirects

- [Issue AaGsF8v1DE2kr22yY43o](https://sonarcloud.io/project/issues?id=Joaomagh_credpay&open=AaGsF8v1DE2kr22yY43o) | `githubactions:S6506` | SECURITY:MEDIUM | legado `MAJOR` | `OPEN`.
- Local: `.github/workflows/credpay-kubernetes-ci.yml:97`. Prioridade proposta: **P1**.
- Avaliação: Melhoria concreta de defesa do CI; exploração não demonstrada.

O executável kind baixado do GitHub usa curl --location. O alerta pede limitar os protocolos dos saltos seguidos pelo cliente.

**Evidência e limite.** credpay-kubernetes-ci.yml:97 verifica SHA256 antes de chmod/execução. A exceção de node privilegiado continua restrita ao runner descartável autorizado.

**Ação.** Exigir HTTPS para URL e redirects, sem mudar checksum, baseline, privilégios ou escopo do cluster.

**Aceite.** kind da baseline baixa e valida; downgrade recusado; Kubernetes CI continua com cleanup próprio e checks verdes.

### S08 - Download de kubectl no Kubernetes CI sem restrição explícita de redirects

- [Issue AaGsF8v1DE2kr22yY43p](https://sonarcloud.io/project/issues?id=Joaomagh_credpay&open=AaGsF8v1DE2kr22yY43p) | `githubactions:S6506` | SECURITY:MEDIUM | legado `MAJOR` | `OPEN`.
- Local: `.github/workflows/credpay-kubernetes-ci.yml:99`. Prioridade proposta: **P1**.
- Avaliação: Melhoria concreta de defesa do CI; exploração não demonstrada.

Outra ocorrência S6506 no mesmo workflow, agora no cliente kubectl obtido de dl.k8s.io. É um download distinto e precisa de verificação própria.

**Evidência e limite.** Linha 99 fixa kubectl1.36.4, com SHA256 na linha seguinte. A configuração usa kubeconfig próprio em vez do contexto pessoal de João.

**Ação.** Restringir requisição/redirects a HTTPS, mantendo checksum e kubeconfig isolado.

**Aceite.** Download e versão do cliente aprovados, downgrade recusado e validação Kubernetes preservada.

### S09 - Download de Gitleaks no Secret Scan sem restrição explícita de redirects

- [Issue AaGsF8v4DE2kr22yY43r](https://sonarcloud.io/project/issues?id=Joaomagh_credpay&open=AaGsF8v4DE2kr22yY43r) | `githubactions:S6506` | SECURITY:MEDIUM | legado `MAJOR` | `OPEN`.
- Local: `.github/workflows/secret-scan.yml:36`. Prioridade proposta: **P1**.
- Avaliação: Melhoria concreta de defesa do CI; exploração não demonstrada.

A ferramenta que procura segredos é baixada por curl com redirects. O transporte de todos os saltos deve manter a política HTTPS.

**Evidência e limite.** secret-scan.yml:36 usa release oficial fixa e SHA256; o canário exige código42 e o scanner usa redação. Não foi identificado vazamento por este achado.

**Ação.** Adicionar restrição HTTPS sem alterar canário, versões, hashes, regras ou exceções do scanner.

**Aceite.** Canário positivo, entrada limpa e histórico passam nos critérios existentes; download inseguro recusado e nova análise.

### S10 - Download de Trivy no inventário sem restrição explícita de redirects

- [Issue AaGsF8vyDE2kr22yY43m](https://sonarcloud.io/project/issues?id=Joaomagh_credpay&open=AaGsF8vyDE2kr22yY43m) | `githubactions:S6506` | SECURITY:MEDIUM | legado `MAJOR` | `OPEN`.
- Local: `.github/workflows/vulnerability-inventory.yml:90`. Prioridade proposta: **P1**.
- Avaliação: Melhoria concreta de defesa do CI; exploração não demonstrada.

O mesmo padrão aparece ao baixar o scanner de dependências e imagens. A mudança proposta reforça a cadeia de aquisição da ferramenta.

**Evidência e limite.** vulnerability-inventory.yml:90 fixa Trivy0.75.0 e confere SHA256 antes da extração. Os achados de dependências registrados no inventário são um conjunto separado dos achados SAST deste relatório.

**Ação.** Exigir HTTPS na URL e em redirects, conservando os quatro alvos, metadados e política de inventário.

**Aceite.** Scanner validado, quatro relatórios completos, cobertura do inventário preservada e downgrade recusado.

### S11 - Traversal na URL de download dos pacotes da pesquisa de sandbox

- [Issue AaGsF8vlDE2kr22yY43e](https://sonarcloud.io/project/issues?id=Joaomagh_credpay&open=AaGsF8vlDE2kr22yY43e) | `pythonsecurity:S7044` | SECURITY:MEDIUM | legado `MAJOR` | `OPEN`.
- Local: `infra/agent-sandbox/core_ci.py:88`. Prioridade proposta: **P2**.
- Avaliação: Fluxo sinalizado com validações relevantes existentes; hipótese de exploração pendente.

S7044 rastreia um caminho lido do JSON de pacotes até urlopen(origin + path). Se esse valor contornar as validações, pode alterar o recurso requisitado. O fluxo da ferramenta rotula a origem genericamente como HTTP, mas no código ela vem de um lockfile local versionado.

**Evidência e limite.** package_baseline chama validate_packages antes do download: nomes esperados, arquitetura amd64, path pool/main, caracteres permitidos, recusa de segmentos '..', extensão .deb, tamanho limitado e SHA256. A origem é uma constante Debian/Ubuntu. O teste existente rejeita pool/main/../../outside_amd64.deb. Redirects finais de urlopen ainda merecem análise própria.

**Ação.** Ampliar testes para caminho absoluto, host, percent-encoding, query, fragmento e redirecionamento; explicitar URL normalizada e política de destino se necessário. Preservar hash/tamanho e isolamento da pesquisa.

**Aceite.** Casos maliciosos recusados antes da requisição; origem/caminho e redirects caracterizados. Decisão fundamentada no SonarQube, sem liberar o core experimental.

### S12 - Senha fictícia do banco de transações no manifesto de exemplo

- [Issue AaGsF8vtDE2kr22yY43j](https://sonarcloud.io/project/issues?id=Joaomagh_credpay&open=AaGsF8vtDE2kr22yY43j) | `yaml:S2068` | SECURITY:MEDIUM | legado `MAJOR` | `OPEN`.
- Local: `infra/k8s/secret.example.yaml:9`. Prioridade proposta: **P1 antes da publicação**.
- Avaliação: Credencial pública deliberada de ensaio; proibida para ambiente externo.

S2068 detectou tx-password em secret.example.yaml:9. O arquivo declara valores fictícios. Kubernetes Secret/stringData não criptografa o conteúdo versionado.

**Evidência e limite.** verify-startup.ps1 aplica esse exemplo no cluster descartável. Portanto, o valor é usado em ensaio; não deve ser descrito como mera documentação sem execução. Nenhuma credencial real foi identificada nesta inspeção.

**Ação.** Manter o escopo descartável explícito e impedir uso desse manifesto na publicação. Provisionar senha exclusiva por destino fora do Git, com permissões mínimas e validação que recuse exemplos conhecidos.

**Aceite.** Plano externo recusa a credencial de exemplo; segredo real não aparece no repositório, chat ou logs. Para o ensaio local atual, triagem contextual separada (P3).

### S13 - Senha fictícia do banco de processamento no manifesto de exemplo

- [Issue AaGsF8vtDE2kr22yY43k](https://sonarcloud.io/project/issues?id=Joaomagh_credpay&open=AaGsF8vtDE2kr22yY43k) | `yaml:S2068` | SECURITY:MEDIUM | legado `MAJOR` | `OPEN`.
- Local: `infra/k8s/secret.example.yaml:11`. Prioridade proposta: **P1 antes da publicação**.
- Avaliação: Credencial pública deliberada de ensaio; proibida para ambiente externo.

A ocorrência da linha 11 é proc-password, vinculada ao banco do segundo serviço. É distinta da senha de transações e deve conservar identidade e privilégios próprios.

**Evidência e limite.** O manifesto é aplicado apenas pelo roteiro de teste conferido; os valores são públicos e conhecidos. Não há prova de vazamento de uma senha privada neste achado.

**Ação.** Provisionar uma credencial exclusiva do processamento no destino externo e impedir reutilização do exemplo ou compartilhamento indevido entre bancos.

**Aceite.** Cada serviço acessa somente seu banco com credencial do destino, exemplos são recusados e nenhum segredo é versionado. Revisão do exemplo local permanece P3.

### S14 - Senha fictícia do RabbitMQ no manifesto de exemplo

- [Issue AaGsF8vtDE2kr22yY43l](https://sonarcloud.io/project/issues?id=Joaomagh_credpay&open=AaGsF8vtDE2kr22yY43l) | `yaml:S2068` | SECURITY:MEDIUM | legado `MAJOR` | `OPEN`.
- Local: `infra/k8s/secret.example.yaml:13`. Prioridade proposta: **P1 antes da publicação**.
- Avaliação: Credencial pública deliberada de ensaio; proibida para ambiente externo.

A linha 13 contém rabbit-password de demonstração. Se esse valor for reutilizado num broker exposto, o conhecimento da senha pública remove uma barreira de acesso.

**Evidência e limite.** Uso presente no ensaio Kubernetes descartável. Banco, AMQP e management precisam continuar internos na futura demo pública. Nenhum acesso externo com esse exemplo foi comprovado.

**Ação.** Gerar credencial exclusiva no destino, definir permissões do broker e manter portas administrativas/AMQP internas. Bloquear uso do manifesto de ensaio no deployment público.

**Aceite.** Verificação externa não alcança AMQP/management; credencial de exemplo recusada, permissões revisadas e nenhum segredo em logs/Git. Contexto local P3.

### Q01 - Regex de zeros finais no diário da criação

- [Issue AaGsF8vODE2kr22yY43Y](https://sonarcloud.io/project/issues?id=Joaomagh_credpay&open=AaGsF8vODE2kr22yY43Y) | `typescript:S8786` | RELIABILITY:MEDIUM | legado `MAJOR` | `OPEN`.
- Local: `frontend/src/app/creation-journal.ts:21`. Prioridade proposta: **P1**.
- Avaliação: Custo superlinear observado em experimento isolado.

creation-journal.ts:21 usa /0+$/ sem âncora inicial para remover zeros finais. Uma sequência longa de zeros seguida de outro dígito força tentativas em várias posições.

**Evidência e limite.** Node24.22.1/Windows, uma medição por tamanho: 4096 zeros 11,94ms; 8192 41,58ms; 16384 164,62ms. O registro carregado é limitado a65536 caracteres. Experimento da expressão, sem ataque integrado ao navegador ou backend.

**Ação.** Substituir por varredura linear mantendo identidade decimal/expoente com BigInt, recibo original e rejeição de registro inválido. Escrever o teste red antes de alterar comportamento.

**Aceite.** Casos adversariais e decimais equivalentes passam sem arredondamento; suíte Angular, build e nova análise preservados. Evitar teste dependente de milissegundos exatos.

### Q02 - Regex de agrupamento monetário na apresentação

- [Issue AaGsF8vODE2kr22yY43Z](https://sonarcloud.io/project/issues?id=Joaomagh_credpay&open=AaGsF8vODE2kr22yY43Z) | `typescript:S8786` | RELIABILITY:MEDIUM | legado `MAJOR` | `OPEN`.
- Local: `frontend/src/app/transaction.ts:24`. Prioridade proposta: **P1**.
- Avaliação: Custo superlinear observado em experimento isolado.

transaction.ts:24 usa lookahead repetido para inserir separadores de milhar. Para cada posição, a regex pode percorrer novamente boa parte do número.

**Evidência e limite.** Na mesma medição: 4096 dígitos 12,59ms; 8192 51,71ms; 16384 260,29ms. O custo ocorre no cliente. Isso não demonstra DoS remoto do servidor; os limites atuais do contrato também precisam entrar na avaliação integrada.

**Ação.** Agrupar a string em blocos em tempo linear, preservando centavos, zeros, expoentes e valores além do inteiro seguro sem converter dinheiro para Number.

**Aceite.** Testes red/green de entradas grandes, exatas e inválidas; renderização responsiva, 45 cenários atuais e nova análise sem regressão.

### Q03 - Label da moeda sem controle associado

- [Issue AaGsF8vMDE2kr22yY43X](https://sonarcloud.io/project/issues?id=Joaomagh_credpay&open=AaGsF8vMDE2kr22yY43X) | `Web:S6853` | RELIABILITY:MEDIUM | legado `MAJOR` | `OPEN`.
- Local: `frontend/src/app/create-page.html:9`. Prioridade proposta: **P2**.
- Avaliação: Problema de semântica/acessibilidade no frontend entregue.

create-page.html:9 apresenta moeda fixa com um label, mas não existe campo editável ao qual associá-lo. Pode confundir a interpretação por tecnologia assistiva.

**Evidência e limite.** A moeda é exibida em um div com BRL/Real brasileiro. O input de valor já tem label com for=amount; o alerta é sobre a moeda fixa.

**Ação.** Usar texto de legenda apropriado para informação estática, ou output/grupo com nome acessível se necessário; não criar controle fictício só para satisfazer a regra.

**Aceite.** Nome da moeda acessível, navegação por teclado preservada e teste semântico antes da mudança; conferir desktop/mobile com a skill de QA.

### Q04 - Teste de cancelamento sem expect reconhecido pelo analisador

- [Issue AaGsF8tsDE2kr22yY43W](https://sonarcloud.io/project/issues?id=Joaomagh_credpay&open=AaGsF8tsDE2kr22yY43W) | `typescript:S2699` | MAINTAINABILITY:BLOCKER | legado `BLOCKER` | `OPEN`.
- Local: `frontend/src/app/transactions-api.spec.ts:43`. Prioridade proposta: **P2**.
- Avaliação: Asserção de fronteira já existe; possível limitação de reconhecimento.

transactions-api.spec.ts:43 testa a saída da tela e usa HttpTestingController.expectNone após cancelar a assinatura. O Sonar não reconheceu essa chamada como asserção e classificou o impacto de manutenibilidade como Blocker.

**Evidência e limite.** expectOne/expectNone da API de teste Angular falham se a quantidade de requisições divergir. O cenário não é um teste vazio. A classificação Blocker é de manutenibilidade, sem impacto de segurança associado na resposta coletada.

**Ação.** Caracterizar emissão/cancelamento com uma asserção de estado observável, sem substituir a verificação de ausência de HTTP. Se mantida a implementação, justificar a limitação por ID.

**Aceite.** Teste falha quando há consulta após unsubscribe ou emissão indevida; continua comprovando ausência de novo HTTP. Nenhuma asserção tautológica.

### Q05 - Fixture sombreia Invoke-WebRequest

- [Issue AaGsF8vcDE2kr22yY43c](https://sonarcloud.io/project/issues?id=Joaomagh_credpay&open=AaGsF8vcDE2kr22yY43c) | `powershelldre:S8625` | RELIABILITY:MEDIUM, MAINTAINABILITY:MEDIUM | legado `MAJOR` | `OPEN`.
- Local: `infra/compose/check-diagnostic-http.ps1:27`. Prioridade proposta: **P3**.
- Avaliação: Sombreamento intencional em controle de teste; avaliar isolamento.

check-diagnostic-http.ps1:27 substitui Invoke-WebRequest por função que lança erro. Ela impede uma chamada HTTP real se o adapter escapar da fixture.

**Evidência e limite.** É um script de verificação, com docker também interceptado, não o adapter de produção. Renomear apenas a função faria perder a proteção do fallback nativo.

**Ação.** Conferir escopo e restauração; preferir isolamento explícito/injeção da fronteira somente se isso mantiver o controle negativo. Justificar o teste se a regra não distingue fixtures.

**Aceite.** O teste continua falhando diante de fallback HTTP real, sem contaminar uma sessão que execute outros comandos.

### Q06 - Fixture sombreia Start-Sleep

- [Issue AaGsF8vaDE2kr22yY43b](https://sonarcloud.io/project/issues?id=Joaomagh_credpay&open=AaGsF8vaDE2kr22yY43b) | `powershelldre:S8625` | RELIABILITY:MEDIUM, MAINTAINABILITY:MEDIUM | legado `MAJOR` | `OPEN`.
- Local: `infra/compose/check-diagnostic-fault.ps1:45`. Prioridade proposta: **P3**.
- Avaliação: Sombreamento intencional em teste de falhas; avaliar isolamento.

check-diagnostic-fault.ps1:45 define Start-Sleep sem espera para executar os cenários fictícios rapidamente. A regra alerta que o nome esconde um cmdlet nativo.

**Evidência e limite.** O objetivo é controlar tempo na fixture. Remover o stub sem estudar o desenho pode reintroduzir atrasos reais nos controles.

**Ação.** Isolar o stub no escopo do teste e verificar que não afeta a execução posterior; refatorar a fronteira de tempo só se houver ganho concreto.

**Aceite.** Ordem causal e recuperação continuam verificadas; nenhuma espera real ou alteração persistente de cmdlet fora da fixture.

### Q07 - Variável $event conflita com nome automático do PowerShell

- [Issue AaGsF8vXDE2kr22yY43a](https://sonarcloud.io/project/issues?id=Joaomagh_credpay&open=AaGsF8vXDE2kr22yY43a) | `powershelldre:S8626` | RELIABILITY:MEDIUM | legado `MAJOR` | `OPEN`.
- Local: `infra/compose/diagnostic-evidence.ps1:94`. Prioridade proposta: **P2**.
- Avaliação: Correção pequena recomendada para confiabilidade do diagnóstico.

diagnostic-evidence.ps1:94 usa $event como UUID do evento da outbox. PowerShell ignora caixa em nomes de variáveis; $Event também tem significado no mecanismo de eventos.

**Evidência e limite.** O roteiro já passou no contexto ensaiado, mas o nome pode depender do escopo/contexto de execução. O alerta não relata acesso indevido a transações.

**Ação.** Renomear para $creationEventId em todos os usos dessa função e verificar o diagnóstico afetado, sem mudar SQL, identidade ou prova causal.

**Aceite.** Mesmo snapshot e prova de recuperação, inclusive com um contexto que tenha variável de evento; controles e ensaio causal preservados.

### Q08 - Regex de identificação ELF na pesquisa de sandbox

- [Issue AaGsF8vfDE2kr22yY43d](https://sonarcloud.io/project/issues?id=Joaomagh_credpay&open=AaGsF8vfDE2kr22yY43d) | `python:S8786` | RELIABILITY:MEDIUM | legado `MAJOR` | `OPEN`.
- Local: `infra/agent-sandbox/project_core.py:65`. Prioridade proposta: **P3**.
- Avaliação: Custo/limites de entrada ainda precisam de caracterização.

project_core.py:65 combina buscas multilinha com espaços repetidos para conferir cabeçalhos ELF. A regra S8786 aponta possível backtracking não linear.

**Evidência e limite.** Esse parser opera no controlador da pesquisa de sandbox, fora da API pública. O experimento de regex do frontend não foi aplicado aqui e não serve como prova para este item.

**Ação.** Medir entradas adversariais de saída de ferramenta e preferir parse por linha/limites explícitos, mantendo rejeição de arquitetura, loader e RPATH divergentes.

**Aceite.** Testes red/green e limites caracterizados antes de modificar; o core experimental continua sem liberação.

### Q09 - Referência Stitch principal sem título HTML

- [Issue AaGsF8v9DE2kr22yY43s](https://sonarcloud.io/project/issues?id=Joaomagh_credpay&open=AaGsF8v9DE2kr22yY43s) | `Web:PageWithoutTitleCheck` | RELIABILITY:MEDIUM | legado `MAJOR` | `OPEN`.
- Local: `docs/frontend/references/code.html:3`. Prioridade proposta: **P3**.
- Avaliação: Arquivo histórico de referência, fora do bundle Angular.

docs/frontend/references/code.html:3 não contém title. Se servido como página, perde identificação acessível da aba.

**Evidência e limite.** O caminho é material de design preservado; não está no diretório src nem na lista de assets de produção do Angular.

**Ação.** Preservar o original. Se o arquivo for transformado em página publicada, corrigir na implementação derivada. Documentar eventual escopo do scanner sem apagar o achado histórico.

**Aceite.** Referência continua íntegra e não é publicada por engano; title da aplicação entregue permanece verificado.

### Q10 - Referência Stitch export-02 sem título HTML

- [Issue AaGsF8v_DE2kr22yY43t](https://sonarcloud.io/project/issues?id=Joaomagh_credpay&open=AaGsF8v_DE2kr22yY43t) | `Web:PageWithoutTitleCheck` | RELIABILITY:MEDIUM | legado `MAJOR` | `OPEN`.
- Local: `docs/frontend/references/export-02/code.html:3`. Prioridade proposta: **P3**.
- Avaliação: Arquivo histórico de referência, fora do bundle Angular.

A página export-02/code.html:3 também não possui title. É uma ocorrência separada do arquivo principal.

**Evidência e limite.** Não integra os fontes/assets da aplicação. A falha seria relevante para a página se ela fosse publicada diretamente.

**Ação.** Preservar original e aplicar requisito de título somente a uma derivação executável/publicada.

**Aceite.** Sem mudança destrutiva da referência; publicação contém apenas o bundle revisado.

### Q11 - Label sem associação na referência export-02

- [Issue AaGsF8v_DE2kr22yY43u](https://sonarcloud.io/project/issues?id=Joaomagh_credpay&open=AaGsF8v_DE2kr22yY43u) | `Web:S6853` | RELIABILITY:MEDIUM | legado `MAJOR` | `OPEN`.
- Local: `docs/frontend/references/export-02/code.html:32`. Prioridade proposta: **P3**.
- Avaliação: Defeito do mockup histórico; não comprova defeito idêntico no produto.

export-02/code.html:32 tem label sem controle/nome acessível válido, mesma família de regra Web:S6853.

**Evidência e limite.** O produto Angular tem sua própria ocorrência Q03 e precisa de teste separado; corrigir a referência não corrige o produto.

**Ação.** Manter rastreabilidade de design e verificar semântica na implementação real.

**Aceite.** Q03 validado no frontend entregue; original de referência preservado.

### Q12 - Elemento interativo sem semântica nativa na referência export-02

- [Issue AaGsF8v_DE2kr22yY43v](https://sonarcloud.io/project/issues?id=Joaomagh_credpay&open=AaGsF8v_DE2kr22yY43v) | `Web:S6848` | RELIABILITY:MEDIUM, MAINTAINABILITY:LOW | legado `MAJOR` | `OPEN`.
- Local: `docs/frontend/references/export-02/code.html:177`. Prioridade proposta: **P3**.
- Avaliação: Acessibilidade do mockup; fora da aplicação executável.

export-02/code.html:177 usa interação num elemento sem semântica/teclado adequados. Controles nativos normalmente oferecem essas propriedades por padrão.

**Evidência e limite.** A ocorrência tem impactos de confiabilidade Medium e manutenibilidade Low. Não possui impacto de segurança no inventário coletado.

**Ação.** Nas telas implementadas, usar button/link de acordo com a ação e validar teclado; não editar o arquivo histórico só para baixar contagem.

**Aceite.** Teclado e nomes acessíveis das ações reais conferidos no QA; referência preservada.

### Q13 - Truncamento por operação bit a bit na referência export-02

- [Issue AaGsF8v_DE2kr22yY43w](https://sonarcloud.io/project/issues?id=Joaomagh_credpay&open=AaGsF8v_DE2kr22yY43w) | `javascript:S7767` | MAINTAINABILITY:HIGH, RELIABILITY:HIGH | legado `MAJOR` | `OPEN`.
- Local: `docs/frontend/references/export-02/code.html:268`. Prioridade proposta: **P3**.
- Avaliação: High de confiabilidade/manutenibilidade em mockup, sem impacto Security.

export-02/code.html:268 usa |0. Essa operação converte para inteiro de32 bits e pode mudar números grandes; Math.trunc comunica truncamento sem essa conversão.

**Evidência e limite.** O código está em referência visual, fora do bundle. O dinheiro do produto usa strings/BigDecimal; não aplicar Math.trunc ao dinheiro como solução.

**Ação.** Preservar o original e proibir reutilização desse cálculo no fluxo monetário. Corrigir somente uma derivação executável, se criada.

**Aceite.** Testes de precisão do produto preservados; origem e classificação High registradas sem apresentá-la como High de segurança.

### Q14 - Truncamento por operação bit a bit na referência export-03

- [Issue AaGsF8wCDE2kr22yY43x](https://sonarcloud.io/project/issues?id=Joaomagh_credpay&open=AaGsF8wCDE2kr22yY43x) | `javascript:S7767` | MAINTAINABILITY:HIGH, RELIABILITY:HIGH | legado `MAJOR` | `OPEN`.
- Local: `docs/frontend/references/export-03/code.html:346`. Prioridade proposta: **P3**.
- Avaliação: High de confiabilidade/manutenibilidade em mockup, sem impacto Security.

export-03/code.html:346 contém o mesmo padrão |0, com risco de conversão para inteiro de32 bits.

**Evidência e limite.** Ocorrência distinta em outro export preservado. Não integra os assets ou fontes da aplicação Angular.

**Ação.** Conservar o arquivo de origem e garantir que a implementação real continue usando cálculo decimal exato.

**Aceite.** Nenhum código dessa referência é executado na demo por acidente; regressões de dinheiro exato continuam verdes.

## Experimentos e limites

- Consulta pública de issues, análises, medidas, hotspots e metadados das regras, sem usar senha ou token. Retorno: 28 issues OPEN e 0 hotspots; zero hotspots não significa ausência de vulnerabilidades.
- Inspeção dos arquivos locais no mesmo SHA da análise. As classificações contextuais são avaliação de engenharia, não conclusões emitidas pelo SonarQube.
- `python -I -B -m unittest discover -s infra/agent-sandbox -p test_*.py`: 71 descobertos, 70 passaram e 1 ignorado por plataforma Windows. Nenhum Docker ou controle real de contenção foi executado nesta revisão; o skip permanece explícito.
- Regex: execução isolada com Node24.22.1/Windows, uma amostra por tamanho. Para 4096/8192/16384 dígitos, remoção de zeros finais: 11,94/41,58/164,62ms; agrupamento: 12,59/51,71/260,29ms. Resultado sustenta investigar algoritmo; não é benchmark estatístico nem prova de ataque ponta a ponta.
- A análise automática não importa cobertura. Há dois avisos observados: versão Python assumida 3.10-3.14 e testes Python sem sonar.tests configurado. Antes de configurar a versão, conferir a baseline do executor.
- Distribuição retornada sem Java. A causa da omissão não foi comprovada; confirmar ingestão dos dois módulos no CI antes de concluir sobre o backend.
- Inventário Trivy e achados GnuTLS são evidências separadas; não foram reexecutados nem encerrados por esta revisão. Release pessoal e publicação também conservam seus critérios.
- Não foram alterados código executável, regras, severidades, exclusões, estados de issues, quality gate, permissões ou configurações remotas. Nenhum deploy ou aceite de risco.

## Fontes

- [Projeto e lista de issues](https://sonarcloud.io/project/overview?id=Joaomagh_credpay)
- [Issues públicas, consulta de 28 itens](https://sonarcloud.io/api/issues/search?componentKeys=Joaomagh_credpay&ps=500&additionalFields=rules)
- [Análise e revisão do código](https://sonarcloud.io/api/project_analyses/search?project=Joaomagh_credpay&ps=5)
- [Distribuição de linguagens](https://sonarcloud.io/api/measures/component?component=Joaomagh_credpay&metricKeys=ncloc_language_distribution)
- [Limites da análise automática](https://docs.sonarsource.com/sonarqube-cloud/analyzing-source-code/automatic-analysis.md)
- [curl: protocolos nos redirecionamentos](https://curl.se/docs/manpage.html#--proto-redir)
- [Revisão de código no SHA analisado](https://github.com/Joaomagh/credpay/tree/ccdd3e65e68b759e76b85ae821891485b9e61f1c)
