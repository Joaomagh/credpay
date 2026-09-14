# CredPay — Tarefas

> Uma sessão = um resultado pequeno e verificável. Atualizar ao encerrar; executar apenas o próximo comportamento aprovado e registrar evidências antes de avançar.

## Próximo

- [ ] Conectar `CriarTransacaoService` ao `TransacaoRepository` em transação local por TDD e comprovar no `TransacaoHttpTest` que `POST /transacoes` persiste antes de retornar `201`, sem RabbitMQ ou consulta HTTP.
