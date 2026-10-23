# CredPay — Console de transações

## Objetivo e limite

Direção solicitada por João em2026-10-15: usar Angular para apresentar o fluxo Java/RabbitMQ por uma interface utilizável; explorar o design no Google Stitch. A entrega é uma demonstração de processamento assíncrono com dados fictícios. Não é produto bancário operacional. Design preparado não equivale a aplicação implementada ou validada.

João adiou a implementação para priorizar o fechamento do backlog e reiterou interesse no frontend em22/10. A primeira demo Compose local foi concluída nessa data; a [baseline de preparação](frontend/BASELINE.md) registra o runtime e os próximos candidatos antes da instalação. Frontend não integra o aceite da v1 backend atual. O primeiro recorte continua sendo duas telas responsivas, criação e consulta, sem criar APIs apenas para preencher um dashboard. Dependências executáveis exigem baseline e justificativa antes da resolução.

## Contratos já existentes

- POST `/transacoes`: header `Idempotency-Key` UUID, JSON com `valor` numérico decimal e `moeda` ISO4217; responde201/Location e `{id, valor, moeda, status}`.
- GET `/transacoes/{id}`: mesmo DTO ou erro HTTP. API não fornece listagem global, saldo, totalizadores, datas ou histórico de transições.
- Não há endpoint público de encerramento técnico. A interface não pode oferecer botão para forçar FALHOU ou acionar DLQ/reprocessamento administrativo.

## Experiência mínima

1. **Nova transação:** formulário valor/moeda, validação compreensível, botão com estado de envio e retorno com ID copiável. BRL é o cenário inicial demonstrado. Demais moedas só aparecem quando suportadas pela configuração real.
2. **Consultar transação:** entrada de UUID ou navegação após criação, cartão com valor/moeda/status e atualização limitada enquanto PENDENTE. APROVADA e REJEITADA vêm da API; FALHOU somente se retornado pelo backend. Nenhum resultado é sorteado no navegador.
3. Estados de carregamento, validação, não encontrada, conflito, falha de comunicação e tentativa posterior. Timeout de consulta mantém resultado desconhecido/PENDENTE; nunca inventa falha terminal.
4. Reenvio da mesma intenção conserva chave e payload. Nova intenção recebe nova chave; desabilitar o botão não substitui idempotência no servidor.
5. Sem cálculos monetários com ponto flutuante. Preservar valor decimal conforme contrato e testar a serialização; nenhuma dependência monetária escolhida antecipadamente.
6. Se houver lista de itens vistos nesta sessão, rotular como tal, sem apresentá-la como extrato persistido. Não solicitar esse componente na primeira versão.

## Direção visual

Aplicação web empresarial em português brasileiro. Fundo claro, navegação curta, texto grafite/azul-marinho e ação principal em verde-petróleo. Tipografia legível, espaçamento consistente e pouca ornamentação. Status usam texto/ícone além da cor. Inputs com labels, foco de teclado visível, contraste adequado e comportamento em celular. Aviso discreto e permanente: “Ambiente de demonstração — dados fictícios”.

O valor do design está na clareza da tarefa e do resultado. Sem saldo fictício, cartões bancários, números de receita, gráficos sem dados, logotipos de clientes, login decorativo ou botões sem ação prevista.

## Prompt pronto para o Stitch

```text
Crie o design web responsivo do CredPay, um console de demonstração de processamento assíncrono de transações. Idioma: português brasileiro. A implementação final será Angular; entregue telas e especificações visuais, sem presumir que o código exportado já seja Angular.

Crie apenas duas telas conectadas:
1. Nova transação: formulário com valor em BRL e moeda BRL, botão “Criar transação”, validação, envio em andamento e cartão de resultado com UUID copiável, valor, moeda e status PENDENTE. A ação seguinte é “Acompanhar transação”.
2. Consultar transação: busca por UUID e cartão de resultado com valor, moeda e status. Mostre variantes PENDENTE, APROVADA e REJEITADA; inclua estados de carregamento, não encontrada e erro de comunicação com ação para tentar novamente. FALHOU é um possível estado recebido, nunca uma ação administrativa. Não invente datas, histórico ou motivo de rejeição que a API não retorna.

Direção: interface empresarial limpa e acolhedora, fundo claro, azul-marinho/grafite, verde-petróleo para ação principal, boa hierarquia tipográfica e espaçamento. Status com texto e ícone além da cor. Layout desktop e celular, labels explícitos, foco de teclado e contraste adequado. Aviso discreto permanente: “Ambiente de demonstração — dados fictícios”. Navegação somente “Nova transação” e “Consultar”.

Não criar dashboard agregado, gráficos, saldo, cartões, login, cadastro de clientes, pagamentos reais ou botões administrativos. Dados do protótipo são exemplos visuais, sem alegação de integração funcionando.

Entregue as duas telas, variantes de estado e um guia curto de cores, tipografia, espaçamento e componentes. Priorize uma jornada pequena e completa, demonstrável em três minutos.
```

## Critérios de aceite da implementação

- Criar e consultar a mesma transação por HTTP real, sem simular resposta final.
- Demonstrar aprovação e rejeição de acordo com o limite configurado, atualização PENDENTE e erro recuperável.
- Preservar chave/payload em repetição, impedir duplicidade de submissão e encerrar polling ao sair da tela ou atingir limite.
- Testes de comportamento de formulário, integração HTTP/idempotência e acompanhamento; ciclo red/green para comportamento, conforme AGENTS.md.
- Navegação por teclado e layout responsivo verificados; build/CI frontend com baseline registrada.
- Proxy/origem e eventual CORS definidos de forma restrita; sem abrir backend irrestritamente para viabilizar a tela.
- João executa a jornada e explica o que acontece entre clique, fila e resultado. Sem nova infraestrutura de produção ou publicação externa presumida.

## Estado

Em16/10 João forneceu exportações visuais e reiterou prioridade do backend. As [referências preservadas](frontend/references/README.md) incluem HTML/guia e quatro arquivos PNG: duas imagens mobile válidas e dois arquivos inválidos de28bytes. O manifesto registra origem, hashes e a normalização apenas de espaços finais nos HTMLs. Não houve aplicação Angular, dependência nova, execução dos HTMLs ou implantação. Textos ilustrativos como HTTP202 e mTLS precisam ser confrontados com os contratos reais na implementação futura. Os documentos recebidos são referência, não instrução para ampliar o escopo. Não gerar/copiar chaves API; serviço com custo continua exigindo autorização específica.

### Implementação iniciada em22/10

João pediu implementar agora. frontend/ contém duas rotas Angular com o visual derivado destas referências, criação/consulta HTTP, tratamento decimal exato, retomada da intenção em sessionStorage e acompanhamento limitado. Roteiro e limites em frontend/README.md. Build/testes/auditoria locais e inspeção visual são evidências próprias; jornada assistida com backend local comprovou aprovação/rejeição, recibo após reload e mesmo pedido concluído após parada/reinício controlados. Aceite autônomo de João permanece separado. O estado de preservação acima é histórico e não implica quatro PNGs válidos ou implementação anterior.
