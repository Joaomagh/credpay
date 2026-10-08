# Triagem inicial — 2026-10-08

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
