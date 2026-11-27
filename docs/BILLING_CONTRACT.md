# Contrato próximo — cobranças e recebimentos

Refinamento de C02–C04 dentro do recorte aprovado em CREDPAY_PLAN.md. Este documento descreve comportamento a implementar; não comprova endpoints disponíveis.

## Criação C02

`POST /cobrancas`, sessão autenticada, CSRF e `Idempotency-Key` UUID obrigatório. `X-CredPay-Conta` opcional confirma a conta esperada, como nas APIs existentes; o proprietário vem da sessão.

Corpo contém somente `clienteId` (UUID), `descricao` (1–240 caracteres após remoção de espaços nas extremidades, sem controles), `valor` (decimal JSON exato positivo), `moeda` (`BRL`) e `vencimento` (data ISO sem horário, anos0001–9999). Valor admite no máximo duas casas significativas, sem arredondamento, e até17 dígitos inteiros. Persistir como `numeric` sem coerção de escala, com CHECKs de faixa e casas significativas: `numeric(19,2)` arredondaria antes da constraint. `10.000` equivale a `10.00`; `10.001` é inválido. A precisão das transações legadas permanece própria. O instante de criação é normalizado a microssegundos antes da escrita, preservando equivalência no replay.

Retorno201 com Location relativo `/cobrancas/{id}`, sem cache, identificador, cliente, descrição, valor/moeda, vencimento e instante de criação UTC. Sem aprovação pelo processador fictício ou publicação de evento legado. Repetição da mesma chave e pedido normalizado na mesma conta devolve o registro original; payload diferente retorna409. Outra conta pode usar a mesma chave sem compartilhar registro.

Cliente ausente ou de outra conta retorna404. Corpo/parâmetros/chave inválidos retornam400; valor/descrição fora das regras retornam422. Não aceitar proprietário, situação ou campos extras pelo corpo. Não revelar a existência de cliente de outra conta.

Persistência exige criação atômica e unicidade `(conta_id,chave)`, além de FK composta `(conta_id,cliente_id)` para clientes. O instante usa Clock. Concorrência da mesma chave deve produzir um registro, sem contas/clientes criados implicitamente. Teste real deve comprovar replay, conflito, isolamento, rollback e commit/reinício antes da tela.

## Consulta C03

`GET /cobrancas/{id}` e lista paginada `GET /cobrancas`, somente da conta autenticada; UUID desconhecido/de outra conta retorna404. Filtros previstos: `situacao=ABERTA|VENCIDA|PAGA`, `limite`1–20, `apos` UUID, sem parâmetros extras/repetidos. Ordenar por UUID para cursor estável. `ABERTA` inclui vencidas. A data atual usa Clock e `America/Sao_Paulo`: antes de hoje sem recebimento é vencida; no dia do vencimento está aberta. Recebida é paga independentemente do vencimento. Não atualizar diariamente uma coluna para representar atraso.

## Recebimento C04

`POST /cobrancas/{id}/recebimento`, sessão/CSRF, somente `dataRecebimento` (data ISO). Registro integral único: valor vem da cobrança, autor da sessão, instante de registro do Clock. Não aceitar valor parcial, autor escolhido ou alteração do histórico por replay. Mesmo pedido devolve registro original; data divergente retorna409. Conta alheia não lê nem registra. Constraint única por cobrança e testes concorrentes comprovam ausência de duplicação.

Data de recebimento futura é recusada; retroativa é permitida como declaração do usuário. A apresentação deve dizer que o recebimento foi informado manualmente, sem confirmação bancária. Correção, estorno, juros e parcelamento exigem regra própria antes de piloto real. O histórico distingue vencimento, data informada e instante do registro.

## Ordem de prova

Domínio red/green → aplicação/idempotência red/green → SQL/constraints/concorrência reais no CI → contrato HTTP/segurança → Angular e Worker → jornada de duas contas com reinício. C07 foi integrado na PR250; C02 é a única frente de implementação.
