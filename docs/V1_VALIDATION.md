# Validação de fechamento do backend

Execuções e inventário de18/10/2026; estado dos checks revalidado e integração realizada em22/10. Candidato da PR178: `7e4233d5d9f952637510f9bc66a76a298b225bdc`, integrado em `ea175e177f9c99ff371cbe30a6f100a72d24db5f`. Checkout de teste do PR: `028f83efff62ead792c35b882bc37b3b1183346f`, com pais `71bf871d8a7b99cc2433ac8047511eee4c334ba4` e o candidato, conferidos pela API GitHub. Este registro não declara a v1 concluída, execução pessoal de João ou inventário atualizado em tempo real.

## Checks do candidato

| Verificação | Run | Resultado |
|---|---|---|
| Transações | [258](https://github.com/Joaomagh/credpay/actions/runs/39101844069) | Sucesso; 522 testes, zero falhas/erros/skips e Checkstyle sem violações |
| Processamento | [251](https://github.com/Joaomagh/credpay/actions/runs/39101844029) | Sucesso; 340 testes, zero falhas/erros/skips e Checkstyle sem violações |
| Fluxo completo | [159](https://github.com/Joaomagh/credpay/actions/runs/39101844013) | Sucesso; diagnóstico sem rota/recuperação e confirmação causal controlada de FALHOU |
| Imagens | [126](https://github.com/Joaomagh/credpay/actions/runs/39101844052) | Sucesso; APROVADA/REJEITADA/replay e OpenSSL/libssl3t64 3.0.13-0ubuntu3.16 nos dois apps |
| Compose | [134](https://github.com/Joaomagh/credpay/actions/runs/39101844019) | Sucesso; preparo, ativação, fluxo/replay e preservação após down/up |
| Kubernetes | [28](https://github.com/Joaomagh/credpay/actions/runs/39101844014) | Sucesso no CI hospedado descartável; persistência, fluxo/replay, parada e recuperação |
| Inventário | [122](https://github.com/Joaomagh/credpay/actions/runs/39101844036) | Sucesso; quatro alvos e artefato selecionado auditados |
| Segredos | [335](https://github.com/Joaomagh/credpay/actions/runs/39101844075) | Sucesso; histórico alcançável e controle positivo do detector |

As duas suítes Maven totalizam862 testes nesse corte. Build/Checkstyle verdes e nenhum warning de conexão Hikari fechada encontrado pela busca específica nos dois logs Maven; erros de constraints em cenários negativos não representam falha da suíte. A revisão remota não tinha reviews, threads ou solicitações pendentes; merge clean e head exato conferidos antes da integração.

## Inventário dos aplicativos

Artefato12127116199: 10.997 bytes, SHA256 `5986f78cea9898bf8bb89ee810bc7b247a7f340b6ab740c8fa75f51933a969e2`. Seis arquivos esperados, nomes únicos, tipos/limites de expansão, UTF-8 estrito, esquema selecionado, checkout, identidades das imagens linux/amd64 e formato dos hashes JAR conferidos. Gitleaks nos arquivos selecionados: zero achados. Revisão independente confirmou bytes/hash/esquema/contagens e comparação com Inventory111.

| Alvo | Pacotes | UNKNOWN | LOW | MEDIUM | HIGH | CRITICAL |
|---|---:|---:|---:|---:|---:|---:|
| transacoes-jar | 107 | 0 | 0 | 3 | 0 | 0 |
| processamento-jar | 107 | 0 | 0 | 3 | 0 | 0 |
| transacoes-image | 243 | 0 | 22 | 32 | 0 | 0 |
| processamento-image | 243 | 0 | 22 | 32 | 0 | 0 |

Trivy0.75.0; VulnerabilityDB atualizada em18/10 às00:43:37UTC e JavaDB às00:58:00UTC. Multiconjuntos de pacotes e registros de achados, incluindo Installed/Fixed/Status/URL, iguais ao Inventory111 nos quatro alvos. Não somar JAR e imagem como CVEs únicos. Achados MEDIUM/LOW permanecem visíveis e sujeitos à [triagem](../infra/security/TRIAGE.md); este corte não significa inventário vazio ou aceite de pagamentos reais.

A primeira conferência local tentou comparar Installed com Version de todos os pacotes e parou: para OS, a projeção selecionada omite os campos separados de epoch/release; Installed dos findings conserva a versão completa. A verificação foi corrigida para comparar identidade Java completa e nomes OS, sem inventar revisão ausente. A auditoria não revalida o relatório bruto Trivy descartado nem reconstitui todas as revisões OS dessa projeção. Bytes originais dos JARs/imagens não foram baixados para novo hash independente. PostgreSQL, RabbitMQ, build, Kubernetes e sandbox estão fora desses quatro inventários.

## Atualização do inventário em22/10

[Inventory124](https://github.com/Joaomagh/credpay/actions/runs/39862739218) e [SecretScan337](https://github.com/Joaomagh/credpay/actions/runs/39862739139) passaram no head documental `dd4c9faba54a9bb2fcd4dc68c3487b096ba449d1`, integrado pela PR179 em `65fe4ebcb79e61cc2541bc18d963f95664f58d90`. Nenhum código da aplicação, Dockerfile ou configuração de container mudou após o candidato testado pela PR178; as imagens foram reconstruídas e têm novos IDs. Checkout de teste `76db612d9e6da2af64ab8f1b5f4034217bfd8013`, pais ea175e1/dd4c9fa conferidos via API.

Artefato12461853306:11.115 bytes, SHA256 `819f59c2d5b2a6a6ea63a6026615b60332114c09f3ddef1d6cf4b83d8decb516`; seis arquivos esperados auditados e Gitleaks0. Esquema selecionado, cobertura107/243 e identidades das imagens conferidos contra os arquivos selecionados; multiconjuntos de pacotes inalterados e revisão independente concluída. Isso não comprova bytes idênticos entre reconstruções. Trivy0.75.0, VulnerabilityDB atualizada em22/10 às19:14:47UTC e JavaDB às01:08:13UTC. As contagens da tabela permanecem: HIGH0/CRITICAL0, MEDIUM3 por JAR e MEDIUM32/LOW22 por imagem.

Os JARs não tiveram delta de achados. Cada imagem teve18 alterações apenas de metadados: nove IDs em curl/libcurl4t64 ganharam Fixed8.5.0-2ubuntu10.16 e Statusfixed; a versão instalada continua8.5.0-2ubuntu10.15. IDs: CVE-2026-86409/86414/87862/87863/87864/92388/92389/92391/94266. São36 registros alterados nas duas imagens, sem novos IDs, severidades, versões instaladas ou pacotes. Fixed/Status do scanner indicam disponibilidade de correção; não demonstram correção instalada. Preservar esses LOW e avaliar atualização oficial de runtime em manutenção posterior, sem alterar pacotes manualmente na imagem ou aceitar segurança universal por HIGH0.

Limites da projeção e do rehash permanecem os mesmos. O log do novo job120117245956 também foi lido em memória pelas seis classes de regex e candidatos JSON financeiro: zero candidatos; não é Gitleaks sobre o log bruto. Nova tentativa de recuperação histórica em22/10 manteve cinco listagens vazias e Transport closed no job111053029171, sem inferir expiração, exclusão ou conteúdo limpo.

## Limites de fechamento

Em22/10, sete logs dos jobs de serviços/fluxo/imagens/Compose/Kubernetes/inventário foram relidos somente em memória: seis classes de regex para credenciais (AWS, GitHub, OpenAI, Slack, cabeçalho de chave privada e URL com credencial) e linhas candidatas a JSON financeiro valor/moeda ou amount/currency retornaram zero candidatos. Não é Gitleaks sobre logs brutos nem prova de ausência de todos os formatos. Só contagens/limites foram preservados localmente; nenhum log bruto foi versionado. O log Secret Scan não foi incluído nesse recorte; seu resultado é o check separado acima.

João separou explicitamente a pesquisa de sandbox da v1 do backend em18/10. Baselines e findings do core continuam preservados, incompletos e sem aceite/liberação de uso pelo agente. Frontend e painel também são posteriores; governança e restrições de AGENTS.md continuam aplicáveis.

A disposição de um log e cinco artefatos históricos não verificados continua pendente, separada do recorte de sandbox. A [revisão de publicação](../infra/security/PUBLICATION_REVIEW.md) não se torna completa por estes checks. Preflight, execução local/Desktop e ensaio/explicação por João continuam sem comprovação; o [roteiro guiado](DEMO.md) está preparado. Nenhuma tag/release ou declaração de 100% é emitida aqui.
