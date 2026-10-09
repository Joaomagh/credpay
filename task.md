# CredPay — Tarefas

> Um incremento = um resultado pequeno e verificável. Papéis em `docs/roles/`, prioridade/aceite em `docs/BACKLOG.md` e evidências em `spec.md`. Revisão e checks antes de avançar.

## Último incremento

- B08.5b integrado na PR #124 (5b105d5): explicação do produto e direção FALHOU registrada; UTF-8/links/diff/revisão e Scan #126 verdes no head 8f68a8f. Ensaio por João ainda necessário.

## Agora

- [ ] B08.6b.2 — Jackson BOM 2.21.7 nos dois serviços: verify local 168/75 e JARs alinhados. Aguardar suítes reais, Flow/Images/Compose/Scan, inventário sem cinco CVEs e revisão antes de integrar.

## Próximo

- [ ] B08.6b.3 — Refinar baseline compatível do RabbitMQ Java client; corrigir os quatro HIGH e provar publicação/consumo/recuperação reais.

## Depois

- Tratar JDBC/runtime e achados restantes, política de bloqueio e revisão de publicação. Ensaiar demo; definir mecanismo/testes FALHOU. Kubernetes depende de direção específica (PR #118 inativa); observabilidade/sandbox e v1 pendentes.
