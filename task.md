# CredPay — Tarefas

> Um incremento = um resultado verificável. Prioridades em docs/BACKLOG.md; evidências em spec.md.

## Último incremento

- PR #155/e739515 integrou revisão delimitada B08.1 (Inventory118/Scan265 verdes), com lacunas preservadas. João adiou Prometheus/Grafana da v1; experimento completo38101da preservado na PR154/branch, sem integrar imagens reprovadas. Exportação/diagnóstico HTTP e quatro ZIPs originais permanecem; spec9.99/16.2.

## Agora

- [ ] B08.7 — Refinar/implementar sandbox-core diagnóstico já aprovado em spec4.3: Debian slim/UID10001/ferramentas e limites próprios, sem Java/Temurin ou topologia de agente presumida. Fixar baseline/inventário e mecanismo de teste de rede controlado antes de instalar/executar; testes negativos reais no CI, sem Docker local nem privilégios adicionais.

## Próximo

- B08.1: recuperar um log/cinco artefatos históricos ou registrar disposição explícita; revisão continua parcial. Depois, ensaio do quickstart/demo/explicação com João. Referências frontend preservadas: quatro PNGs (dois válidos/dois inválidos de28bytes) e quatro ZIPs originais; docs/frontend/references/README.md.

## Depois

- Frontend e painel Prometheus/Grafana somente depois da v1, com nova baseline quando retomados. Nenhum aceite de risco ou implantação externa autorizado pelo adiamento.
