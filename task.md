# CredPay — Tarefas

> Um incremento = um resultado pequeno e verificável. Papéis em `docs/roles/`, prioridade/aceite em `docs/BACKLOG.md` e evidências em `spec.md`. Revisão e checks antes de avançar.

## Último incremento

- B08.5b na PR #124: explicação do produto/tecnologias/etapas e direção FALHOU registrada; referências/UTF-8/diff e revisão passaram. Integração somente após Secret Scan do head final. Tomcat já integrado na #123/175e139; riscos restantes preservados.

## Agora

- [ ] B08.6b.2 — Após integrar #124, refinar baseline compatível do Jackson e corrigir os achados; preservar Java 21/Boot 3, repetir suítes reais e inventário antes da integração.

## Próximo

- Concluir tratamento dos demais componentes/imagens e política de bloqueio; ensaiar apresentação com João.

## Depois

- Concluir tratamento de dependências/imagens e política de bloqueio, ensaiar demo com João. Refinar mecanismo/testes FALHOU: retry esgotado mantém PENDENTE recuperável; encerramento exige confirmação irrecuperável. Kubernetes aguarda direção específica (PR #118 inativa); observabilidade/sandbox e v1 não concluídos.
