# CredPay — Tarefas

> Um incremento = um resultado verificável. Prioridades em docs/BACKLOG.md; evidências em spec.md.

## Último incremento

- PR #158/a0667ae integrou core mínimo após Sandbox Core9/Scan277 verdes no head1c2c558:26 controles,20 comandos,88 arquivos,45 diretórios/2 links e28 componentes parciais; spec9.101.

## Agora

- [ ] B08.7c — Integração final da PR159 após revisão/gates do último SHA. CI12/Scan280 e34 controles verdes, artefato conferido; primeiros negativos de contenção: filesystem/bind/temporários, ambiente e privilégios, recriação e alteração-canário detectada. Fixtures próprias no CI, relatório com controles positivos e limites; spec9.102. AJ-NET/HOST/RES/TOOLS completos/scanner e DockerDesktop continuam etapas próprias.

## Próximo

- B08.7d: refinar inventário de vulnerabilidades do core parcial, preservando origem Debian e cobertura real; depois negativos pendentes. Não interpretar OS0 como baseline limpa.
- B08.1: recuperar um log/cinco artefatos históricos ou registrar disposição explícita; revisão continua parcial. Depois, ensaio do quickstart/demo/explicação com João. Referências frontend preservadas: quatro PNGs (dois válidos/dois inválidos de28bytes) e quatro ZIPs originais; docs/frontend/references/README.md.

## Depois

- Frontend e painel Prometheus/Grafana somente depois da v1, com nova baseline quando retomados. Nenhum aceite de risco ou implantação externa autorizado pelo adiamento.
