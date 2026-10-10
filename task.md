# CredPay — Tarefas

> Um incremento = um resultado verificável. Papéis em docs/roles/, prioridades em docs/BACKLOG.md e evidências em spec.md.

## Último incremento

- B06.3 integrado na PR #131/668d73b: contador do publicador do processador, sete testes novos, suíte121/8m13 e seis gates finais verdes em bbf99f2. Sem nova exposição HTTP/dependência; três IDs Spring/OpenSSL permanecem. Spec9.78.

## Agora

- [ ] B06.4 — Completar diagnóstico no publicador de TransacaoCriada. Mesmo contrato de tentativas/outcomes, TDD em testes existentes e prova de deltas na fixture real de dois casos. Preservar mensagens, exceções, interrupção, timeout e ociosidade. Desenho revisado; sem implementação iniciada. Spec9.79.

## Próximo

- [ ] Refinar leitura operacional das métricas após instrumentar os dois publicadores, preservando health padrão e exposição somente explícita.

## Depois

- Mecanismo FALHOU, sandbox verificável, demo e v1 pendentes. Kubernetes exige direção específica (PR #118 inativa). Spring/OpenSSL e política de bloqueio permanecem separados; consulta oficial09/10 não identificou runtime corrigido, sem aprovação de risco.
