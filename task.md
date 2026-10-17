# CredPay — Tarefas

> Um incremento = um resultado verificável. Prioridades em docs/BACKLOG.md; evidências em spec.md.

## Último incremento

- PR167/3ca6ec5 integrou recusa local de PIDs/recuperação após Core33/Scan305 verdes no head3d4eaf2;53testes Linux sem skips,52locais/1skip POSIX explícito; spec9.110. Baseline com CVEs continua sem aceite.

## Agora

- [ ] B08.7f.1 — Implementar primeiro corte TCP/UDP conforme docs/SANDBOX_NETWORK_PROTOCOL.md: mesma imagem, fixtures none, positivo em namespace próprio compartilhado e worker negativo separado, positivos antes/depois e prazos limitados. Red/green56descobertos/55passlocais/1skip POSIX; implementação em revisão/CI, ainda sem prova real; spec9.113.

## Próximo

- B08.7: negativos rede/daemon/ferramentas em incrementos separados; DockerDesktop pendente.
- Remediação da baseline: priorizar gawk e análise de separação do código conforme infra/agent-sandbox/TRIAGE.md, sem mistura de pacotes sid/supressão/aceite implícito.
- B08.1: recuperar um log/cinco artefatos históricos ou registrar disposição explícita; revisão continua parcial.
- João: primeiro ensaio guiado em docs/DEMO.md, começando pelo preflight; execução e explicação ainda não comprovadas.

## Depois

- Frontend/painel depois da v1; referências em docs/frontend/references/README.md. Nenhum aceite de risco ou implantação externa autorizado.
