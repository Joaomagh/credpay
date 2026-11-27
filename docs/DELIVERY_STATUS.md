# Estado da entrega — 27/11/2026

Este quadro é um checkpoint, não um aceite de publicação. Evidências detalhadas em spec§9.176–9.177 e prioridades em BACKLOG.md.

| Item | Estado comprovado | Próximo passo | Depende de João |
| --- | --- | --- | --- |
| Clientes C01 | Integrado na main, API e Angular, isolamento e persistência testados | Preservar regressões | Não |
| Google C07 — backend | Maven integrado699/0 falhas/erros/skips no f59b75f; inclui SQL/HTTP e contas isoladas | Integrado na PR250/main df45ca5 após todos os gates; falta configuração real | Não para os testes fictícios |
| Google C07 — interface e proxy | Tela83 testes Angular+2 Node/build; Worker96 contratos+4 runtime locais | Integrado na PR250; falta ensaio Google real/configuração | Não |
| Google real | Projeto ainda não configurado; roteiro em GOOGLE_SETUP.md | Criar projeto/cliente e cadastrar redirect definitivo; segredo fora do Git/chat | Sim, conta e credenciais |
| Cobranças C02 | Domínio/serviço em red/green local; SQL ainda stub | Contrato HTTP, TDD de valor/vencimento/cliente/idempotência e persistência | Não |
| Consultas C03 | Planejado | Filtros aberta/paga/vencida, relógio e isolamento | Não |
| Recebimento manual C04 | Planejado | Registro integral único, repetição/conflito e histórico | Não |
| Publicação gratuita | Compose aprovado; sem hospedagem ou domínio próprio; teto zero | Endereços gratuitos, HTTPS, retenção, recuperação e ensaio no destino | Sim, contas/destino e etapas pessoais de configuração |
| Banco e segurança da base | Candidata não promovida; pendências históricas preservadas | Restore em volume novo, inventários e triagem conforme MVP_CLOSURE_RUNBOOK.md | Eventuais acessos aos serviços |
| Demonstração C05 | Ainda sem jornada completa de cobranças | Percorrer Angular/API/banco; depois obter feedback real | Sim para feedback e aceite pessoal |
| Provedor C06 | Posterior ao primeiro recorte | Retomar com baseline e necessidade concreta | Sim para ampliar escopo externo |

Publicar o código na PR não publica a aplicação. Testes do emissor fictício não comprovam login no Google real. Não há movimentação financeira, piloto ou adoção real comprovados.
