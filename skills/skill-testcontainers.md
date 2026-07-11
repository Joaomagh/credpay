# Guia repetível — Testcontainers (PostgreSQL + RabbitMQ)

Pré-requisito: Docker disponível. Fixe versões das imagens no build; não use `latest`. Prefira um teste base compartilhado somente após surgir repetição real.

```java
@Testcontainers
@SpringBootTest
class FluxoIntegracaoTest {
    @Container
    static PostgreSQLContainer<?> postgres =
        new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("credpay")
            .withUsername("test")
            .withPassword("test");

    @Container
    static RabbitMQContainer rabbit =
        new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.rabbitmq.host", rabbit::getHost);
        registry.add("spring.rabbitmq.port", rabbit::getAmqpPort);
    }

    @Test
    void contexto_deveSubir_comPostgresERabbitReais() {
        assertThat(postgres.isRunning()).isTrue();
        assertThat(rabbit.isRunning()).isTrue();
    }
}
```

Dependências de teste: `org.testcontainers:junit-jupiter`, `postgresql` e `rabbitmq`, usando o BOM do Testcontainers para alinhar versões.

Checklist de um teste útil:

- deixar Flyway criar o schema; não usar DDL mágico exclusivo de teste;
- publicar/consultar comportamento real, em vez de testar apenas `isRunning()`;
- aguardar mensagens com timeout curto (ex.: Awaitility), nunca `Thread.sleep`;
- isolar dados entre testes e evitar dependência de ordem;
- capturar logs ao falhar e encerrar containers automaticamente;
- não reutilizar container como requisito de CI; reuso local é apenas otimização.

O smoke test acima valida a configuração inicial. O próximo teste deve provar uma migration, constraint ou fluxo de mensagem concreto.
