# Inventário de vulnerabilidades dos apps

B08.6a gera quatro relatórios: dependências de cada JAR e pacotes de cada imagem derivada. Todos são produzidos pelo checkout indicado no manifesto. Os hashes dos JARs e IDs/arquitetura das imagens acompanham o relatório. Bibliotecas aparecem no JAR e na imagem; as contagens por alvo não representam vulnerabilidades únicas.

## Baseline e execução

O inventário declara a arquitetura esperada: AMD64 por padrão, ARM64 somente por `-ExpectedArchitecture arm64` em scan/report. As identidades dos dois manifestos e a metadata dos dois scans de imagem devem coincidir com o perfil; conteúdo recebido nunca seleciona automaticamente a arquitetura aceita. JSON/summary registram a arquitetura, sem remover achados. Matriz nativa de runners24.04/24.04-arm preserva o artefato AMD64 e publica ARM64 em nome distinto. Baseline da variante Trivy0.75.0 Linux-ARM64 SHA256a1ee9f6ffb7d112b64ff726a2a0717c21175c1114361391f4a132956751a13b3 em spec§9.163; hash oficial conferido antes da extração. Resultados reais ainda precisam de triagem, mesmo com job verde.

### Transporte das ferramentas de CI

Os downloads curl externos declarados nos workflows exigem `--proto '=https' --proto-redir '=https'`. O checksum continua obrigatório antes de extrair/executar o binário. `test_https_downloads.py` lê as opções dessas chamadas e prova, com curl real e servidor HTTP somente em127.0.0.1, recusa do HTTP inicial antes da conexão e do redirect HTTP antes de chegar ao destino. Um controle positivo sem restrição comprova que cliente e fixture funcionam. Para isolar o redirect, somente a primeira requisição da fixture é explicitamente HTTP; downloads reais permanecem HTTPS e TLS não é desabilitado.

Executar da raiz: `python -I -B -m unittest discover -s infra/security -p 'test_https_downloads.py'`. Usa Python stdlib e curl já instalado, sem instalação automática, Docker, certificado/chave fictícia ou acesso a `.curlrc`: o teste passa `--disable` e desliga proxies para suas conexões loopback. O Secret Scan executa esse controle antes de baixar Gitleaks. A prova de negociação HTTPS com os servidores oficiais fica nos workflows reais; esta fixture não é um teste TLS completo nem uma allowlist de egress do runner.

### Scanner de vulnerabilidades

O workflow `Vulnerability Inventory` empacota os dois módulos Java 21 e constrói as imagens existentes. Resolve [Trivy 0.75.0](https://github.com/aquasecurity/trivy/releases/tag/v0.75.0) Linux amd64 dentro de `.local/security`, com SHA256 `c6e65abddb348e25f10549df887045629cf28cc72453cd1c63acb717316b3f3f`. Não instala ferramentas globalmente nem adiciona dependências aos apps. Actions estão fixadas por commit; upload-artifact v7.0.2 usa `cf430e030ddbb5b0abf93d22962f4752f3646cd9`.

As bases de vulnerabilidades e Java requerem downloads; versões e datas são preservadas no inventário. O binário fixado não congela a base consultada. A rede do runner não é uma allowlist de egress nem comprova sandbox.

O scanner lê os [arquivos Docker exportados](https://trivy.dev/docs/latest/target/container_image/) sem acessar o socket. A [análise Java](https://trivy.dev/docs/latest/coverage/language/java/) reconhece dependências dentro do JAR. `--list-all-pkgs` permite conferir cobertura, inclusive quando não existem achados. JAR empacotado usa `rootfs` no diretório isolado com somente `app.jar`: [o modo fs desabilita análise de pacotes individuais nesta versão](https://github.com/aquasecurity/trivy/blob/v0.75.0/pkg/commands/artifact/run.go). `scan.ps1` exige sucesso dos quatro scans; `report.ps1` exige schema, bibliotecas esperadas, pacotes OS nas imagens, metadados das bases e identidade dos alvos.

Controle local, sem Docker, scanner ou rede:

```powershell
./infra/security/check-reports.ps1
```

São nove controles sintéticos: quatro alvos válidos com contagens e exclusão de ambiente; rejeição de relatório vazio, biblioteca ausente, OS ausente, base ausente, SHA inválido, imagem inválida, relatório de imagem trocado e severidade desconhecida. Configuração operacional não recebe red artificial de negócio. O scan real acontece no CI; Docker local indisponível não é contornado.

## Publicação e triagem

A [revisão delimitada de publicação](PUBLICATION_REVIEW.md) registra os recortes de logs/artefatos, o achado histórico de corpos fictícios de testes e a correção prospectiva. Um log e cinco artefatos históricos permanecem sem conteúdo revisto; B08.1 não está integralmente concluído. Detecção de segredos e inventário de vulnerabilidades têm escopos distintos.

Somente `inventory.json`, `summary.md` e manifestos selecionados são publicados, por sete dias. O JSON usa uma lista explícita de campos públicos de pacotes/achados; não publica configuração de ambiente, histórico da imagem, payloads, tars, relatórios brutos ou logs integrais. Nenhuma supressão, `ignore-unfixed` ou `continue-on-error` é usada.

Achados não interrompem este inventário inicial; erro operacional, falta de cobertura ou relatório inválido interrompem. Isso não aprova segurança. B08.6b deve revisar HIGH/CRITICAL, aplicabilidade, versão corrigida e ação concreta antes de definir o bloqueio. Um CVE detectado não prova exploração nem pode ser descartado apenas porque não há versão corrigida.

O escopo inclui somente dependências empacotadas e as duas imagens dos apps. PostgreSQL, RabbitMQ, ferramentas de build e Kubernetes exigem análises próprias. A suíte funcional existente continua responsável pelos comportamentos da aplicação.

## Proposta de bloqueio da entrega externa

A [triagem atual](TRIAGE.md) registra a correção dos três IDs HIGH/CRITICAL originais e a validação da migração B08.6c. Inventory100 reconheceu os quatro alvos completos com HIGH0/CRITICAL0, preservando MEDIUM/LOW. A proposta é impedir entrega externa enquanto houver HIGH/CRITICAL sem correção ou disposição explícita aprovada pelo Navigator. Uma disposição teria de identificar CVE, componente/versão, condições, evidências e lacunas, alcance, responsável, prazo e revisão; nenhuma foi aprovada.

O workflow continua validando e publicando o inventário; não existe gate automatizado de severidade implementado. Sua conclusão verde comprova execução, identidade e cobertura, sem liberar produção. A implementação de um gate separado exige política definida e controles de bloqueio/aprovação técnica; não se altera o relatório bruto para passar. Implantação externa continua exigindo autorização específica.
