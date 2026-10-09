# Triagem inicial — 2026-10-08

**Estado consolidado em 2026-10-09:** JDBC integrado na PR #127; Inventory #13 registra HIGH0/JAR1/imagem e CRITICAL2/alvo. Os três IDs restantes e a proposta de publicação estão no final deste documento. As tabelas e preparações anteriores preservam o histórico.

Fonte: [Inventory #2](https://github.com/Joaomagh/credpay/actions/runs/37843133113), head `e947fb7`, checkout de merge da PR `3ae9d97b82995a4e4cbc30a24996812a42df567b`. Os quatro relatórios passaram cobertura e identidade. Artefato selecionado: seis arquivos, SHA256 `a6f5ed68255b2a8e5e5f2758937ea6588390737a32f990ae8bfa5a9398fd7363`, retenção sete dias. Esta triagem parcial preserva o resultado; não aprova segurança ou define exceções.

| Alvo | Pacotes | LOW | MEDIUM | HIGH | CRITICAL |
|---|---:|---:|---:|---:|---:|
| transacoes-jar | 71 | 1 | 12 | 10 | 5 |
| transacoes-image | 214 | 22 | 27 | 11 | 5 |
| processamento-jar | 71 | 1 | 12 | 10 | 5 |
| processamento-image | 214 | 22 | 27 | 11 | 5 |

UNKNOWN=0 em todos os alvos. As contagens se repetem entre apps/JAR/imagem; não somar como CVEs únicos. Cada imagem contém 143 pacotes Ubuntu e 71 dependências Java reconhecidas.

## Prioridade do próximo incremento

| Componente instalado | Indicação de correção no scan | Próxima ação |
|---|---|---|
| Tomcat embed core 10.1.55 | 10.1.58, mantendo a linha 10.1 | Verificar os três CRITICAL e advisories Apache; escolher atualização compatível e repetir suítes/fluxo/scan |
| Jackson core/databind 2.21.4 | 2.21.7 na mesma linha | Verificar cinco HIGH e BOM coerente; parser HTTP recebe dados externos, não presumir ausência de exposição |
| RabbitMQ Java client 5.25.0 | 5.34.0 cobre versões indicadas nos quatro HIGH | Conferir advisories/compatibilidade com Spring AMQP e repetir testes reais de publicação/consumo |
| PostgreSQL JDBC 42.7.11 | 42.7.12 | Conferir advisory HIGH e repetir persistência/migrations/fluxo |
| libssl3 3.0.2-0ubuntu1.29 | 3.0.2-0ubuntu1.30 | Conferir Ubuntu e escolher runtime oficial atualizado por digest; não alterar pacotes manualmente no container |
| Spring MVC 6.2.19 | Scan indica 7.0.9 | Investigar condições e suporte antes de propor mudança de major; não misturar Framework 7 com Boot 3 |

Versões da tabela são indicações do scanner, não baseline já aprovada/resolvida. Verificar disponibilidade, advisories e BOM antes de baixar dependências novas. Uma atualização deve provar redução dos achados e preservar comportamento; não reduzir controles ou ocultar CVEs para produzir verde.

## Condições Spring já investigadas

[CVE-2026-47884](https://spring.io/security/cve-2026-47884/) depende de XsltView e renderização sem nome explícito sob mapeamento amplo. [CVE-2026-47890](https://spring.io/security/cve-2026-47890/) depende de SSE com fragmentos de views e dados controlados por atacante. Os advisories Spring classificam esses casos como MEDIUM/LOW; o inventário registra CRITICAL conforme sua base. Preservar ambas as informações, sem reclassificar o relatório bruto.

Busca em `transacoes-service/src/main` e `processamento-service/src/main` não encontrou XsltView, SseEmitter, ServerSentEvent, FragmentsRendering ou text/event-stream. O controller de transações usa REST/JSON. Isso sugere ausência das condições descritas no código atual, mas não é prova dinâmica, exceção aprovada ou ausência de outros riscos. A correção OSS indicada muda de major; a versão 6.2.20 aparece como suporte Enterprise nas fontes oficiais. Não contratar serviço nem migrar arquitetura automaticamente.

## Aceite de B08.6b

Revisar todos os HIGH/CRITICAL do inventário, vincular condições/fontes e uma ação por componente; definir baseline compatível antes da resolução, atualizar em incrementos pequenos e repetir scan e suítes afetadas. Política de bloqueio e eventuais exceções precisam de justificativa estreita, prazo e revisão. Este documento não cria ignorefile, supressão, aceite de risco ou certificação.

## Atualização Tomcat — B08.6b.1

[Inventory #5](https://github.com/Joaomagh/credpay/actions/runs/37844620033), head a7972fb, checkout0a746a28: CRITICAL5→2 por alvo, com71 pacotes/JAR e214/imagem mantidos; HIGH10/JAR11/imagem permanecem. Tomcat core/EL/WebSocket10.1.60 foram conferidos nos dois JARs locais. Fonte Apache informa que10.1.58 indicada inicialmente não foi publicada;10.1.60 é versão disponível com correções posteriores. Os dois CRITICAL exibidos no JAR de transações são Spring47884/47890. Fluxo/imagens/Compose/Scan verdes; Transações #194 e Processador #176 também passaram. Integração aguarda checks dos registros documentais. Este registro não apaga a baseline anterior nem encerra a política geral.

## Atualização Jackson — B08.6b.2 integrado

Baseline e fontes dos cinco HIGH registradas no backlog antes da resolução. BOM 2.21.7 aplicado nos dois POMs; JARs locais confirmam core/databind e demais módulos 2.21.7, annotations 2.21 conforme BOM. Verify local 168/75 passou, sem testes de infraestrutura por ausência do engine local. Suítes reais e novo inventário precisam comprovar regressão e ausência dos cinco IDs em cada alvo antes da integração. Nenhum ignorefile, supressão ou aprovação global de segurança. Tomcat já integrado na PR #123/175e139 após sete checks finais verdes em 2cece9b.

Resultado Jackson: PR #125/35c4087 integrada após sete gates verdes do head07df2f0. Inventory #8/run37868732324, log completo dos quatro alvos: nenhum dos cinco IDs Jackson, cobertura71/214, HIGH5/JAR6/imagem e CRITICAL2. Artefato11589766080, SHA25660a39d12daed75edc1ddde9b5022e478d8c71f1be08d4e965cbb20a4bff54f61. Preparação inicial acima registra o estado anterior ao aceite. Restam quatro HIGH RabbitMQ client, um JDBC, OpenSSL nas imagens e dois CRITICAL Spring.

## Cliente RabbitMQ — B08.6b.3 integrado

Baseline e fontes no backlog antes da resolução: amqp-client5.25.0→5.34.0, preservando Spring AMQP3.2.12/Boot3.5.16/Java21. Regressão real e ausência dos quatro IDs em todos os alvos ainda exigidas; nenhuma supressão ou aprovação geral. Runtime: metadata oficial consultada de21-jre-jammy/21.0.12.1_1-jre-jammy aponta ao mesmo digest atual, sem candidata corrigida identificada; [Ubuntu](https://ubuntu.com/security/CVE-2026-84782) indica libssl3 .30 em Jammy. Trocar só a tag não corrige o achado; baseline/digest atualizado precisam de scan antes de adotar.

Inventory #10 bloqueou o aceite Rabbit: quatro IDs removidos, porém Netty transitivo4.1.135 adicionou59901 HIGH/75595 CRITICAL. Cobertura79/222 inclui sete Netty módulos e jctools-core embutido; HIGH2/3 e CRITICAL3. Nova baseline no backlog: netty.version4.1.137.Final para os dois POMs, mesma linha Boot; seis IDs devem desaparecer e todos os gates repetir. Não excluir dependências nem ocultar o achado. Fonte vendor75595 HIGH/scanner CRITICAL preservados; condição não é prova de exploração no aplicativo.

**B08.6b.3 integrado:** PR #126/b5e001f, sete gates verdes em5f5e406; Inventory11 seis IDs ausentes, cobertura79/222, HIGH1/JAR2/imagem CRITICAL2. Evidência completa em spec9.72. Netty transitivo corrigido antes do merge, sem excluir bibliotecas ou reduzir testes.

## PostgreSQL JDBC — B08.6b.4 integrado

Baseline no backlog antes da resolução:42.7.11→42.7.12, CVE-2026-54291, condição channelBinding=require. Correção na mesma linha sem alterar TLS; novo inventário dos quatro alvos e sete gates ainda exigidos. Rabbit/Netty integrado; Spring/OpenSSL permanecem pendentes, sem aprovação global.

## Disposição dos achados restantes — 2026-10-09

Inventory #13/run37871930354 do head96f6f7a validou quatro relatórios completos:79 pacotes por JAR,222 por imagem; HIGH0/JAR1/imagem, CRITICAL2/alvo. JDBC54291 ausente; PR #127/f342d3f integrada após sete gates verdes. Identidade, digest e testes completos em spec9.73. As imagens repetem as bibliotecas dos JARs: três IDs únicos restantes, não somar ocorrências como CVEs distintos.

| ID/componente instalado | Alvos e severidade | Condição e evidência disponível | Correção/ação e lacuna |
|---|---|---|---|
| [47884 — spring-webmvc6.2.19](https://spring.io/security/cve-2026-47884/) | Quatro alvos; scannerCRITICAL/vendorMEDIUM | XsltView, mapeamento amplo /** e nome de view implícito. Controller de aplicação REST/ResponseEntity; buscas e configuração não mostram XSLT/views. | Vendor6.2.20 Enterprise ou7.0.9 OSS. Decidir migração coerente Boot/Framework ou suporte; não inserir Framework7 isolado no Boot3. Busca não é prova dinâmica ou exceção. |
| [47890 — spring-webmvc6.2.19](https://spring.io/security/cve-2026-47890/) | Quatro alvos; scannerCRITICAL/vendorLOW | SSE com fragmentos de views/dados do atacante. Não encontrados SseEmitter, ServerSentEvent, FragmentsRendering ou text/event-stream na produção consultada. | Mesmas linhas corrigidas do vendor. Evidência estática insuficiente para afirmar não afetado; decisão de direção pendente. |
| [84782 — libssl3 3.0.2-0ubuntu1.29](https://ubuntu.com/security/CVE-2026-84782) | Duas imagens; scanner/vendorHIGH | Retransmissão DTLS durante escrita parcial suspensa pode divulgar memória ou derrubar processo. Presença do pacote comprovada; exploração e uso DTLS não testados. | Jammy corrigido em3.0.2-0ubuntu1.30. Consulta registrada em8/10 não identificou digest Java21JREJammy oficial corrigido. Selecionar candidata/digest e provar patch/ausência do ID por scan antes da adoção; não instalar pacote manual ou trocar só tag. |

Mapeamento estático em produção: TransacaoController é @RestController com POST/transacoes e GET/transacoes/{id}, retorno ResponseEntity com DTO; nenhum outro @Controller, WebMvcConfigurer, ViewResolver ou classes de views/SSE encontradas. application.yml dos serviços não configura resolver/view; processador expõe health. Reexaminar ao mudar dependências/configuração; não extrapolar para bibliotecas, autoconfiguração, infraestrutura externa ou prova de ausência de vulnerabilidade.

**Proposta pendente de direção:** entrega externa exige correção ou disposição estreita aprovada para cada HIGH/CRITICAL. Inventário verde permite revisar dados válidos, sem aprovar publicação. Não há gate de severidade, risco aceito, ignorefile ou supressão implementados. João decide migração de major, contratação, família runtime ou eventual disposição de risco com evidência, alcance, responsável, prazo e revisão. Desenvolvimento/revisão do repositório seguem dentro da v1; implantação externa não está autorizada.
