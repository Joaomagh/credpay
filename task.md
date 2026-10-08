# CredPay — Tarefas

> Um incremento = um resultado pequeno e verificável. Papéis em `docs/roles/`, prioridade/aceite em `docs/BACKLOG.md` e evidências em `spec.md`. Revisão e checks antes de avançar.

## Último incremento

- B08.6b.1 integrado na PR #123 (175e139), sete checks verdes no head 2cece9b e revisão sem bloqueante. Tomcat 10.1.60 alinhado; CRITICAL 5→2 por alvo no inventário da correção, demais achados preservados.

## Agora

- [ ] B08.5b — Integrar a explicação do produto, problemas/tecnologias e etapas em docs/ENTENDA_O_CREDPAY.md; registrar direção FALHOU aprovada, sem afirmar implementação ou aprendizado validado.

## Próximo

- [ ] B08.6b.2 — Refinar baseline compatível do Jackson e corrigir achados em um incremento operacional; preservar Java 21/Boot 3, repetir suítes reais e inventário antes da integração.

## Depois

- Concluir tratamento de dependências/imagens e política de bloqueio, ensaiar demo com João. Refinar mecanismo/testes FALHOU: retry esgotado mantém PENDENTE recuperável; encerramento exige confirmação irrecuperável. Kubernetes aguarda direção específica (PR #118 inativa); observabilidade/sandbox e v1 não concluídos.
