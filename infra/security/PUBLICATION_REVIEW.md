# Revisão de publicação — B08.1

Revisão delimitada em 16/10/2026. **Parcial:** um log e cinco artefatos históricos permanecem sem conteúdo revisto. Ausência de matches nos detectores utilizados não prova ausência universal de segredos. Não autoriza implantação externa, reescrita de histórico ou remoção de evidências.

## Cobertura observada

| Recorte fixado | Logs revistos | Artefatos revistos | Limite |
|---|---:|---:|---|
| Histórico: 1.182 runs / 1.189 tentativas |1.188 jobs; 95.932.298 caracteres/773.086 linhas|89 ZIPs de 94 identificados|Um log e cinco ZIPs sem conteúdo revisto|
| Delta enumerado 22:07:07UTC / revisto até 22:18:33UTC em 16/10|80/80; 7.519.707 caracteres/61.812 linhas|9 ZIPs adicionais|Não inclui automaticamente execuções posteriores ao corte|
| PR #153, head `f345887b6ee84b1b2fb0288ac8b3e8c6e4167c30`|8/8; 803.351 caracteres/6.463 linhas|Inventory #111 / artefato12082716656|Candidata fixada; merge `c75f927`|
| PR #154, head `b297b772c5c43fb55a493802b23bd523c0c0cb9f`|Somente Inventory #113; 67.145 caracteres/684 linhas|Artifact de apps12083607435|PR em construção, não é revisão completa da candidata|

Histórico+delta identificaram 103 ZIPs, dos quais 98 foram revistos. Os recortes posteriores estão separados na tabela; não somar tentativas, runs e jobs como se fossem a mesma unidade. Não houve laço contínuo sobre a `main`.

Logs foram processados somente em memória: seis classes de regex (AWS, GitHub, OpenAI, Slack, cabeçalho de chave privada e URL com credencial não mascarada), além de indicadores específicos de dumps MockMvc e corpos financeiros. Não foi Gitleaks sobre os logs originais; logs brutos não foram persistidos. Zero matches de credenciais nos recortes efetivamente revistos; o achado de corpos abaixo permanece registrado. Formatos desconhecidos e conteúdo indisponível ficam fora da conclusão.

Nos ZIPs, a revisão conferiu digest SHA256 do download versus metadados GitHub, nomes/tipos/unicidade/limites de expansão, UTF8 estrito, schema, checkout, manifestos, contagens e coerência técnica. Conteúdo não executado. Gitleaks 8.30.1 foi aplicado aos arquivos extraídos, com controles positivo/negativo conhecidos, redação de achados e zero achados no material revisto. Metadados de hashes dos JARs foram conferidos quanto a formato/relação; os JARs originais não estavam disponíveis para novo hash independente.

## Achado e correção

Dois jobs históricos continham quatro corpos financeiros de testes MockMvc, com dados fictícios identificados. Campos observados: `id`, `valor`, `moeda`, `status`; valores não são reproduzidos aqui. Isso contraria a convenção do projeto de não registrar payload financeiro bruto:

- [run38636150999/job116099716533](https://github.com/Joaomagh/credpay/actions/runs/38636150999/job/116099716533).
- [run38635707815/job116098202786](https://github.com/Joaomagh/credpay/actions/runs/38635707815/job/116098202786).

A correção prospectiva `print=NONE` foi integrada na [PR #149](https://github.com/Joaomagh/credpay/pull/149), merge `7e58bde`. Não apaga nem reclassifica os logs antigos. Não foi confirmada credencial real no material efetivamente revisto.

## Lacunas históricas

O [job111053029171 / run37071897057](https://github.com/Joaomagh/credpay/actions/runs/37071897057/job/111053029171) permaneceu inacessível por `TRANSPORT_CLOSED`. Conteúdo não verificado. Não há base para chamá-lo de expirado ou limpo.

| Artefato sem conteúdo revisto | Run |
|---|---|
|11580670593|37847328359|
|11580181696|37846134850|
|11579665700|37844620033|
|11578354347|37844107153|
|11578772025|37843707080|

Qualquer conclusão ampliada exige recuperar esses conteúdos ou registrar uma disposição específica sobre a lacuna. Não excluir evidências para encerrar o item.

## Inventários posteriores e escopo

Inventory #111 preserva quatro alvos completos: 107 pacotes em cada JAR e 243 em cada imagem, HIGH0 / CRITICAL0; JAR MEDIUM3 e imagem MEDIUM32/LOW22. Trivy 0.75.0; VulnerabilityDB atualizada 2026-10-16T01:13:54.947090392Z e JavaDB2026-10-16T01:00:20.589203513Z. Artefato12082716656 tem SHA256 `8770403da94467589310270362207c3bf9297ffc7c0b44c3f7a4a703cad982e9`, checkout de teste5fb7b4727aa9dd3589e776dfe2e86d29f34cbdea. Não somar JAR/imagem como vulnerabilidades únicas.

Inventory #113 manteve esses quatro alvos, mas recusou o novo perfil de observabilidade: Prometheus com Go431 / OS0, sem inventário adicional válido publicado. Esse resultado não declara Prometheus/Grafana seguros nem amplia quatro alvos para seis. A PR #154 permanece pendente de baseline, triagem e verificações próprias. Execuções futuras exigem recorte explícito; este documento não certifica a última `main` em tempo real.

A revisão não comprova AI-Jail, egress por allowlist, isolamento do host ou segurança dos componentes fora do inventário (PostgreSQL, RabbitMQ, build e Kubernetes). Os JSONs locais com hashes/contagens e detectores permanecem em `.local/security/`, ignorados, sem logs brutos ou URLs temporárias de download. A [baseline do scanner](../../spec.md#161-baseline-de-detecção-de-segredos--b081) e a [triagem de dependências](TRIAGE.md) têm escopos separados.
