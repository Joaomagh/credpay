# Protocolo diagnóstico de rede do core

Refinamento em17/10/2026; implementação inicial em revisão/validação CI (§9.113), ainda sem prova real. Primeiro corte de AJ-NET-01: cliente TCP/UDP funcional e isolamento de namespaces, somente fixtures fictícias no CI hospedado. Não declara bloqueio de internet/DNS/LAN/metadados ou allowlist de egress.

## Mecanismo escolhido

Modo diagnóstico separado, com o mesmo imageID auditado do core e suas20 ferramentas. Nenhuma imagem derivada, runtime novo, bridge, porta publicada ou privilégio adicional. O worker real continua `--network none`; o cliente positivo compartilha somente a rede de uma fixture própria, também criada com `none`. Não compartilha PID/IPC/host nem recebe socket/credenciais.

Por protocolo, sequencialmente:

1. Criar fixture restrita/none com servidor GNUawk `/inet4/{tcp|udp}/PORT/0/0`, porta alta fixa e dados sintéticos.
2. Validar fixture por ID/label/imageID/configuração/StartedAt e criar cliente positivo com `--network container:<fixtureID>`. Essa exceção diagnóstica só aceita o ID próprio previamente validado; `validate_worker` do worker real continua exigindo `none`.
3. Cliente usa `/inet4/{tcp|udp}/0/127.0.0.1/PORT` e exige resposta exata ao nonce. Esse intercâmbio prova readiness; uma mensagem emitida antes da abertura sob demanda não prova bind/accept.
4. Executar a mesma solicitação em worker com namespace separado/none. Exigir falha observada de I/O classificada, sem transformar erro de sintaxe, cliente ausente, erro genérico ou timeout do controlador em PASS.
5. Confirmar novo intercâmbio positivo, com a mesma fixture viva/StartedAt inalterado/RestartCount0. Não aceitar servidor encerrado/reiniciado como controle de uma recusa. Registrar disponibilidade do endpoint e namespaces durante o protocolo; se não puder comprová-la, resultado inconclusivo.
6. Comparar device+inode dos namespaces enquanto vivos: fixture e positivo iguais; negativo distinto. Não comparar identificadores depois da destruição.
7. Remover somente containers validados por ID/label/imagem. Nenhum cleanup global, volume ou rede do usuário.

O controlador usa prazos externos por operação e limite do job. Leituras têm timeout interno, positivos têm retries finitos e nenhuma falha operacional é omitida. Em UDP, enviar antes de ler e fechar/reabrir socket do servidor entre intercâmbios: associação ao primeiro peer não pode produzir falso negativo no segundo cliente. `READ_TIMEOUT` não é prova de limite para connect/accept; o controlador mantém o timeout externo.

## Aceite e evidência

- Testes red/green rejeitam alvo/ID alheio, namespace incompatível, falta de positivo anterior/posterior, nonce incorreto, observação incompleta e falha operacional usada como bloqueio.
- CI real comprova TCP e UDP separadamente, com comandos/configurações selecionadas/hashes/imageID/checkout/UTC/exit/resultados/limites; sem Config/Env completos ou logs brutos.
- PASS é restrito ao cliente e ao isolamento entre namespaces. O endpoint negativo é o loopback do próprio worker, nunca IP real do host, rede local, internet ou serviço de metadados.
- DNS, canários de LAN/metadados e egress completo exigem desenho/testes posteriores; ausência de cliente ou erro DNS genérico não os comprova.
- Baseline do core permanece sem aceite por advisories; o protocolo não promove a imagem para operação do agente nem aplica a exceção privilegiada de Kubernetes.

Fontes primárias: [GNUawk networking](https://www.gnu.org/software/gawk/manual/gawkinet/gawkinet.html), [variáveis de timeout/retries](https://www.gnu.org/software/gawk/manual/html_node/Other-Environment-Variables.html), [Docker none](https://docs.docker.com/engine/network/drivers/none/) e [compartilhamento de namespace](https://docs.docker.com/engine/network/#container-networks). Os mecanismos precisam ser comprovados na baseline efetiva, não aceitos só pela documentação.
