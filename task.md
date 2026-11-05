# CredPay — Tarefas

> Um incremento = um resultado verificável. Prioridades em docs/BACKLOG.md e evidências em spec.md.

## Último incremento

- Corte técnico da v1 e ensaios locais assistidos em docs/V1_VALIDATION.md e docs/V1_CANDIDATE.md.
- Sessão, autorização e idempotência por conta nas PRs #208 a #210. Contratos em spec§9.142–9.144.
- Login, rotas e recibos por conta nas PRs #211 e #212. Contratos em spec§9.145–9.146.
- Oito jornadas reais de navegador, incluindo duas com contas, na PR #213. Evidência em spec§9.147 e no Compose CI.
- Limite de expansão decimal na criação na PR #214. Contrato e red em spec§9.148.
- Orçamentos de frequência e orientação de espera no console na PR #215. Contrato em spec§9.149.
- Relatórios de cobertura dos dois serviços Java e do Angular. Baseline e comandos em spec§9.150.
- Primeira análise SonarQube revisada em 05/11: 28 itens catalogados, incluindo 4 Security High e 10 Medium; relatório e prioridades propostas em docs/SONARQUBE_REVIEW.md e spec§9.151. Nenhum achado encerrado.

## Agora

- [ ] Aceite pessoal da v1 permanece adiado. O roteiro está em docs/DEMO.md e as evidências assistidas em docs/V1_VALIDATION.md.
- [ ] Completar a análise SonarQube pelo CI: confirmar os dois módulos Java e importar dois JaCoCo + LCOV. A análise automática atual não contabiliza Java nem cobertura; prioridades propostas em docs/SONARQUBE_REVIEW.md.

## Próximo

- Depois dos aceites pessoais, conferir a aplicabilidade das evidências e registrar a decisão final sobre o candidato técnico 51f8476.
- Acompanhar os achados GnuTLS registrados no inventário e na triagem.
- Critérios de publicação da demo com contas.
- Aplicar em incrementos separados as prioridades propostas de transporte do CI, custo das regex e revisão contextual por ocorrência; preservar testes e reanalisar cada mudança.
- Preparar o recorte de publicação com HTTPS, retenção e limites validados. Cloudflare e Oracle ainda dependem das contas de João; nenhum recurso externo criado.

## Depois

- Demo pública gratuita após escolher e autorizar o destino. Critérios de publicação em docs/PUBLIC_DEMO_PLAN.md.
- Painel e pesquisa de sandbox seguem adiados.
