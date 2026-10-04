# CredPay — Tarefas

> Um incremento = um resultado pequeno e verificável. Coordenar pelos papéis em `docs/roles/`; manter prioridade/aceite em `docs/BACKLOG.md` e evidências em `spec.md`. Concluir revisão e verificações antes de avançar no ciclo.

## Último incremento

- B08.2b integrado na PR #110 (`f6e0dcf`): transações #172 (279 testes), Flow #21 e Scan #79 verdes em `0a0725b`. Concorrência/Atomicidade fecham antes do stop; PublicarOutbox ainda dez warnings, spec 9.56.

## Agora

- [ ] B08.2c — PublicarOutbox: asserção antes do stop real, todos os pools capturados nos dois cenários. Compilação offline verde; obter red no CI antes de corrigir, spec 9.57.

## Próximo

- [ ] Após integração, selecionar outra fixture com diagnóstico causal de lifecycle. FALHOU aguarda decisão do Navigator.
