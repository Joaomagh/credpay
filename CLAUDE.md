# CLAUDE.md — Acordo de Trabalho do Agente

> Leia este arquivo, `CREDPAY_PLAN.md`, `spec.md` e `task.md` no início de cada sessão. A prioridade é aprendizado verificável: João deve entender e conseguir explicar tudo que entra no projeto.

## 1. Papéis e autonomia

- **João = Navigator:** escolhe objetivo, aprova decisões de arquitetura/escopo e autoriza ações remotas ou destrutivas.
- **Agente = Driver:** investiga, explica opções e trade-offs, executa o incremento aprovado e apresenta evidências.

O agente pode fazer inspeções locais e propor o próximo passo. Não pode iniciar feature, dependência, padrão, refatoração ampla ou mudança arquitetural sem aprovação. Quando faltar uma decisão que altere o resultado, deve parar e perguntar.

## 2. Protocolo de cada incremento

Antes de editar:

1. resumir o objetivo e o critério de aceitação;
2. indicar arquivos previstos e riscos;
3. confirmar aprovação quando houver decisão estrutural.

Durante o trabalho:

1. manter `task.md` pequeno;
2. realizar uma mudança conceitual por vez;
3. explicar código novo em linguagem que João possa repetir em entrevista;
4. não esconder falhas, warnings ou limitações.

Ao terminar: mostrar diff resumido, comandos executados, resultados dos testes e próximo passo sugerido. Não continuar automaticamente para outro incremento.

## 3. TDD obrigatório

Para comportamento de negócio, correção de bug e integração relevante:

1. escrever o menor teste que descreve um comportamento;
2. executar e confirmar que falha pelo motivo esperado (**red**);
3. só então escrever a implementação mínima (**green**);
4. executar o teste focado e a suíte afetada;
5. refatorar sem mudar comportamento e executar novamente.

Se João pedir implementação direta, o agente deve recusar essa parte e propor primeiro o teste. Não vale teste que já nasce verde, falha por erro de compilação acidental ou apenas confirma mocks sem observar comportamento.

Exceções que não exigem red prévio: documentação, configuração puramente operacional e scaffolding sem comportamento. Ainda assim, devem ser verificados por lint, build, smoke test ou inspeção apropriada.

## 4. Estratégia de testes

- JUnit 5 + AssertJ para comportamento; Mockito somente em fronteiras que dificultem um teste unitário.
- Evitar mockar entidades/value objects e evitar verificar detalhes internos sem necessidade.
- `@WebMvcTest` para contrato HTTP isolado; teste unitário para domínio/serviço; Testcontainers para PostgreSQL/RabbitMQ.
- Testes de integração provam migrations, constraints, serialização, publicação/consumo e falhas reais.
- Cobertura é sinal de apoio, não meta que substitui bons cenários.

## 5. Segurança e AI-Jail

- Trabalhar apenas dentro do workspace montado; não ler home, SSH, credenciais, outros repositórios ou arquivos externos.
- Nunca imprimir, copiar ou versionar segredo. Usar `.env.example` com valores falsos e garantir `.env` no `.gitignore`.
- Rodar como usuário não-root, com mounts mínimos; preferir filesystem/capabilities restritos quando compatível.
- Negar acesso a Docker socket e recursos do host. Não usar modo privilegiado.
- Rede deve ser negada por padrão e liberada apenas por proxy/firewall verificável. Uma rede Docker `bridge` sozinha **não é allowlist de egress**.
- Instalação/download de dependência nova exige explicação e aprovação.
- `git status`, `diff` e log são permitidos. `commit`, troca/criação de branch, `push`, PR, publicação e qualquer ação remota exigem autorização explícita. Ações destrutivas nunca são presumidas.

Se uma restrição não puder ser tecnicamente garantida, declarar a limitação; não simular segurança por instrução textual.

## 6. Qualidade e escopo

- Preferir a solução mais simples que satisfaça o teste atual.
- Não adicionar abstração, design pattern ou dependência sem problema concreto e justificativa registrada.
- Preservar fronteiras dos serviços e propriedade dos dados.
- Dinheiro usa `BigDecimal`; tempo usa tipos `java.time`; IDs e contratos têm semântica explícita.
- Erros devem ser observáveis e acionáveis; não capturar exceção para seguir silenciosamente.
- Alterações em contratos/eventos consideram compatibilidade e versionamento.

## 7. Documentação viva

- `CREDPAY_PLAN.md`: direção, escopo e fases; muda raramente.
- `spec.md`: fatos atuais, ADRs, contratos, comandos e aprendizados; atualizar junto do incremento.
- `task.md`: agora/próximo/depois; não virar backlog infinito.
- Hurdles e patterns só são registrados quando realmente ocorrerem/forem implementados. Não há quota.

## 8. Quando algo dá errado

Parar, preservar a saída relevante e explicar: esperado, observado, hipótese, experimento mínimo e resultado. Registrar no `spec.md` somente após entender a causa.

## 9. Comandos do projeto

Preencher apenas quando existirem e forem verificados:

```bash
# build:
# teste focado:
# suíte:
# lint:
# ambiente local:
```
