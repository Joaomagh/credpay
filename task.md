# CredPay — Tarefas

> Um incremento = um resultado verificável. Prioridades em docs/BACKLOG.md; evidências em spec.md.

## Último incremento

- PR163/2b3ad0b integrou quota de CPU sob excesso após Core26/Scan294 verdes no headfdb6b5c;47 controles e artefato conferidos. Baseline com CVEs continua sem aceite; spec9.106.

## Agora

- [ ] B08.7e.2 — Memória sob excesso: positivo/recuperação32MiB, carga nominal limitada1,5GiB sob1GiB/swap0, PID1 observador e eventos locais max/oom/kill. Red/green50 controles locais/Linux, revisão e Core28/Scan298 verdes; artefato auditado, gates finais/merge pendentes; spec9.108.

## Próximo

- B08.7: PID sob excesso e negativos rede/daemon/ferramentas em incrementos separados; DockerDesktop pendente.
- Remediação da baseline: priorizar gawk e análise de separação do código conforme infra/agent-sandbox/TRIAGE.md, sem mistura de pacotes sid/supressão/aceite implícito.
- B08.1: recuperar um log/cinco artefatos históricos ou registrar disposição explícita; revisão continua parcial. Depois ensaio do quickstart/demo/explicação com João.

## Depois

- Frontend/painel depois da v1; referências em docs/frontend/references/README.md. Nenhum aceite de risco ou implantação externa autorizado.
