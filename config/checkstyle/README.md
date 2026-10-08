# Gate mínimo de Java

Baseline de build: Maven Checkstyle Plugin 3.6.0, engine Checkstyle 14.3.0 explícito, Java 21. O engine padrão do plugin é 9.3; a versão explícita evita presumir suporte Java 21. Fontes oficiais: [override do engine](https://maven.apache.org/plugins/maven-checkstyle-plugin/examples/upgrading-checkstyle.html), [runtime e sintaxe suportados](https://checkstyle.org/index.html#JRE_and_JDK).

Uma configuração verifica fontes principais e testes dos dois serviços na fase `validate`, antes de executar testes. Proíbe imports internos JDK (`sun`, `com.sun`, `jdk.internal`), imports curinga, imports não usados, tabs e arquivos Java sem newline final. Aceita LF/CRLF. Não aplica ordenação, limite de linhas, Javadoc obrigatório ou reformatação geral. Não é scanner de vulnerabilidades nem análise completa de tipos/dependências.

Em cada módulo: `./mvnw.cmd --batch-mode --no-transfer-progress validate` no Windows, `./mvnw` no Linux. `verify` também executa o gate. Na raiz, PowerShell 7.3+: `./config/checkstyle/check-controls.ps1`. O parâmetro -Module aceita processamento-service (padrão) ou transacoes-service; cada CI usa seu próprio plugin/wrapper. Em CI, o wrapper Linux deve estar executável.

O roteiro copia o plugin real para um projeto descartável em `.local/checkstyle/<UUID>`, sem alterar fontes reais. Dois controles válidos incluem record, text block e pattern switch Java 21 em LF/CRLF; cinco controles inválidos exigem falha e diagnóstico da regra específica. Logs ficam locais/ignorados. Erro de parser/configuração não comprova uma rejeição válida. Não apagar evidências para conseguir green.

Primeira resolução requer Maven Central; execução offline funciona após preparar o cache. Ferramentas são dependências do plugin de build, sem entrada no JAR dos aplicativos. Suites com PostgreSQL/RabbitMQ continuam exigindo Docker Linux; os controles não as substituem.
