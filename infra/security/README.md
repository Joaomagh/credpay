# Inventário de vulnerabilidades dos apps

B08.6a gera quatro relatórios: dependências de cada JAR e pacotes de cada imagem derivada. Todos são produzidos pelo checkout indicado no manifesto. Os hashes dos JARs e IDs/arquitetura das imagens acompanham o relatório. Bibliotecas aparecem no JAR e na imagem; as contagens por alvo não representam vulnerabilidades únicas.

## Baseline e execução

O workflow `Vulnerability Inventory` empacota os dois módulos Java 21 e constrói as imagens existentes. Resolve [Trivy 0.75.0](https://github.com/aquasecurity/trivy/releases/tag/v0.75.0) Linux amd64 dentro de `.local/security`, com SHA256 `c6e65abddb348e25f10549df887045629cf28cc72453cd1c63acb717316b3f3f`. Não instala ferramentas globalmente nem adiciona dependências aos apps. Actions estão fixadas por commit; upload-artifact v7.0.2 usa `cf430e030ddbb5b0abf93d22962f4752f3646cd9`.

As bases de vulnerabilidades e Java requerem downloads; versões e datas são preservadas no inventário. O binário fixado não congela a base consultada. A rede do runner não é uma allowlist de egress nem comprova sandbox.

O scanner lê os [arquivos Docker exportados](https://trivy.dev/docs/latest/target/container_image/) sem acessar o socket. A [análise Java](https://trivy.dev/docs/latest/coverage/language/java/) reconhece dependências dentro do JAR. `--list-all-pkgs` permite conferir cobertura, inclusive quando não existem achados. JAR empacotado usa `rootfs` no diretório isolado com somente `app.jar`: [o modo fs desabilita análise de pacotes individuais nesta versão](https://github.com/aquasecurity/trivy/blob/v0.75.0/pkg/commands/artifact/run.go). `scan.ps1` exige sucesso dos quatro scans; `report.ps1` exige schema, bibliotecas esperadas, pacotes OS nas imagens, metadados das bases e identidade dos alvos.

Controle local, sem Docker, scanner ou rede:

```powershell
./infra/security/check-reports.ps1
```

São nove controles sintéticos: quatro alvos válidos com contagens e exclusão de ambiente; rejeição de relatório vazio, biblioteca ausente, OS ausente, base ausente, SHA inválido, imagem inválida, relatório de imagem trocado e severidade desconhecida. Configuração operacional não recebe red artificial de negócio. O scan real acontece no CI; Docker local indisponível não é contornado.

## Publicação e triagem

Somente `inventory.json`, `summary.md` e manifestos selecionados são publicados, por sete dias. O JSON usa uma lista explícita de campos públicos de pacotes/achados; não publica configuração de ambiente, histórico da imagem, payloads, tars, relatórios brutos ou logs integrais. Nenhuma supressão, `ignore-unfixed` ou `continue-on-error` é usada.

Achados não interrompem este inventário inicial; erro operacional, falta de cobertura ou relatório inválido interrompem. Isso não aprova segurança. B08.6b deve revisar HIGH/CRITICAL, aplicabilidade, versão corrigida e ação concreta antes de definir o bloqueio. Um CVE detectado não prova exploração nem pode ser descartado apenas porque não há versão corrigida.

O escopo inclui somente dependências empacotadas e as duas imagens dos apps. PostgreSQL, RabbitMQ, ferramentas de build e Kubernetes exigem análises próprias. A suíte funcional existente continua responsável pelos comportamentos da aplicação.

## Proposta de bloqueio da entrega externa

A [triagem atual](TRIAGE.md) registra três IDs HIGH/CRITICAL remanescentes. A proposta é impedir entrega externa enquanto houver achado sem correção ou disposição explícita aprovada pelo Navigator. Uma disposição teria de identificar CVE, componente/versão, condições, evidências e lacunas, alcance, responsável, prazo e revisão; nenhuma está aprovada neste incremento.

O workflow continua validando e publicando o inventário; não existe gate automatizado de severidade implementado. Sua conclusão verde comprova execução, identidade e cobertura, sem liberar produção. A implementação de um gate separado exige política definida e controles de bloqueio/aprovação técnica; não se altera o relatório bruto para passar. Implantação externa continua exigindo autorização específica.
