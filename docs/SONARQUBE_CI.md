# SonarQube pelo GitHub Actions

## Escopo e baseline

Projeto público `Joaomagh_credpay`, organização `joaomagh`, região EU já criada por João. Uma análise agrega os dois módulos Java, Angular e infraestrutura versionada. Não exclui os guards da pesquisa nem as referências Stitch para reduzir achados. Classes compiladas e bibliotecas dos dois serviços são entradas obrigatórias; o scanner recusa relatórios vazios, caminhos de cobertura fora dos fontes e fontes ausentes. As regras, prioridades e findings anteriores continuam rastreáveis em [SONARQUBE_REVIEW.md](SONARQUBE_REVIEW.md).

O LCOV Angular atual inclui fontes TypeScript e templates HTML. O preparador preserva ambos os tipos e todas as contagens, alterando somente o prefixo dos caminhos. A compatibilidade e eventuais avisos da ingestão dos templates ainda precisam ser conferidos na primeira execução remota; não remover registros silenciosamente para produzir cobertura aparente.

Ferramenta nova necessária: ação oficial `SonarSource/sonarqube-scan-action` v8.4.1, commit `e805d875275f728fac1885b413934be6d7c63f65`, com Scanner CLI `8.3.1.16491`. Baseline registrada antes de sua execução. A ação usa Node24 e verifica assinatura GPG do scanner por padrão; não ativar `skipSignatureVerification`. Fonte/release e identidade foram conferidas no projeto oficial. A assinatura reduz risco de aquisição, mas fornecedor, scanner e runner continuam parte da base de confiança. [Release](https://github.com/SonarSource/sonarqube-scan-action/releases/tag/v8.4.1) · [Manifesto fixado](https://github.com/SonarSource/sonarqube-scan-action/blob/e805d875275f728fac1885b413934be6d7c63f65/action.yml) · [Licença do fornecedor](https://github.com/SonarSource/sonarqube-scan-action/blob/e805d875275f728fac1885b413934be6d7c63f65/LICENSE.txt).

Reutiliza Java21, Node24.22.1, npm lockfile, JaCoCo0.8.16 e Vitest5.0.5 já registrados. `maven-dependency-plugin:3.9.0:copy-dependencies` é a versão já gerenciada pelo parent Boot4.0.8; copia as dependências resolvidas de teste para `target/sonar-dependencies` em cada módulo. A versão Python informada ao analisador é a do executor dos controles, obtida no próprio job. Nenhum pacote de aplicação, ferramenta global ou serviço pago é adicionado.

## Execução em duas etapas

1. A PR do incremento executa o workflow **SonarQube analysis** com `contents:read`, checkout sem credencial persistida e histórico completo. Roda os controles da preparação, as duas suítes Maven com cobertura, Angular/tipos/build de contas/audit e validação dos três relatórios. Não recebe `SONAR_TOKEN` nem publica análise durante a PR. O workflow publica somente os relatórios de cobertura por14dias.
2. Depois de integrar e conferir a configuração externa, executar manualmente **SonarQube analysis**, branch `main`, opção `analyze=true`. O job confirma a presença do secret antes do build, repete a geração no mesmo checkout e envia a análise com o SHA exato. Aguarda o quality gate configurado por até300segundos e falha se ele falhar. Não reduz condições nem contorna um gate vermelho. `analyze=false` permite repetir apenas a preparação sem contato autenticado com o SonarQube.

A configuração inicial é manual para conferir ingestão e findings antes de tornar a análise um gate obrigatório de toda PR. Esse modo ainda não é proteção contínua do branch. Próximo incremento após a primeira análise CI válida: definir com João as condições de qualidade, o período de código novo, o gatilho automático e a proteção apropriada ao plano gratuito. A análise automática atual não deve ser desligada até a substituição estar preparada e verificada. O projeto não pode usar Automatic Analysis e CI analysis simultaneamente. [Integração GitHub Actions](https://docs.sonarsource.com/sonarqube-cloud/analyzing-source-code/ci-based-analysis/github-actions-for-sonarcloud) · [Conflito entre métodos](https://docs.sonarsource.com/sonarqube-cloud/analyzing-source-code/automatic-analysis.md).

## Credencial e ativação por João

Na conta SonarQube, criar um token destinado a esta análise, com expiração e o menor escopo disponível no plano. No Free, a documentação orienta personal access token; o usuário correspondente precisa de Execute Analysis no projeto. Criar o repository secret `SONAR_TOKEN` em `Joaomagh/credpay` pelo GitHub, nunca no código, em arquivo, argumentos do scanner, logs ou chat. Não enviar o valor ao agente. [Tokens](https://docs.sonarsource.com/sonarqube-cloud/managing-your-account/managing-tokens) · [Autenticação do scanner](https://docs.sonarsource.com/sonarqube-cloud/analyzing-source-code/analysis-parameters/parameters-not-settable-in-ui).

Depois da preparação verde, fazer a troca coordenada do método no projeto: Administration → Analysis Method → desligar Automatic Analysis e executar o job CI em `main`. Conferir plano Free, sem iniciar upgrade/trial pago. Em falha da substituição, preservar o diagnóstico e restabelecer a análise anterior enquanto a causa é tratada; não deixar a desativação sem acompanhamento. Nenhum token deve ser exibido durante essa verificação.

## Conferência da primeira análise

- A análise remota mostra o SHA executado e contabiliza Java dos dois serviços.
- Os dois XML JaCoCo e LCOV foram importados sem avisos de fontes não resolvidas; conferir medidas por arquivo/módulo com as saídas dos testes.
- `sonar.test.inclusions` separa fontes/testes; os dois avisos iniciais de Python precisam ser reavaliados com a versão efetiva e o escopo configurado.
- Os resultados da análise, inclusive novos achados Java, entram na triagem. Gate verde não encerra automaticamente achados anteriores ou autoriza publicação externa.
- A análise pode produzir mais issues que a primeira varredura incompleta. Revisar cada achado; não acrescentar exclusões ou cobertura artificial para baixar a contagem.

## Preparação local sem credencial

Os testes de preparação usam somente fixtures temporárias e nenhuma rede:

```powershell
python -I -B -m unittest discover -s infra/quality -p 'test_*.py'
```

`prepare_sonar.py` completo só passa quando os relatórios, fontes, bytecode e bibliotecas dos dois serviços existem no mesmo checkout. O LCOV normalizado é escrito em `frontend/coverage/console/lcov-sonar.info`, mantendo o original. Nunca enviar `.local`, `.env`, banco, cookies, credenciais ou logs extensos como evidência de cobertura.
