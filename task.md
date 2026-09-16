# CredPay — Tarefas

> Uma sessão = um resultado pequeno e verificável. Atualizar ao encerrar; executar apenas o próximo comportamento aprovado e registrar evidências antes de avançar.

## Próximo

- [ ] Integrar `Idempotency-Key` ao `POST /transacoes` em TDD, cobrindo header ausente/malformado, replay e conflito `409`, sem RabbitMQ ou dependência nova.
