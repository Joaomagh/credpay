# Guia repetível — Testcontainers (PostgreSQL + RabbitMQ)

Pré-requisito: Docker disponível. Fixe versões das imagens no build; não use `latest`. Prefira um teste base compartilhado somente após surgir repetição real.

```java
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

@Testcontainers
@SpringBootTest
class FluxoIntegracaoTest {
    @Container
    static PostgreSQLContainer postgres =
        new PostgreSQLContainer("postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0")
            .withDatabaseName("credpay")
            .withUsername("test")
            .withPassword("test");

    @Container
    static RabbitMQContainer rabbit =
        new RabbitMQContainer("rabbitmq:4.3.5-management-alpine@sha256:b3b8b7f95f5382a19f9ea33540e604f30aad081d37ad9aba72255135765373a1");

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

Dependências de teste na baseline Testcontainers2: `org.testcontainers:testcontainers-junit-jupiter`, `testcontainers-postgresql` e `testcontainers-rabbitmq`, alinhadas pelo BOM gerenciado no Spring Boot4. O PostgreSQLContainer dessa linha não é genérico. Demais imports usuais foram omitidos do recorte; as fixtures executáveis estão em src/test dos serviços.

Checklist de um teste útil:

- deixar Flyway criar o schema; não usar DDL mágico exclusivo de teste;
- publicar/consultar comportamento real, em vez de testar apenas `isRunning()`;
- aguardar mensagens com timeout curto (ex.: Awaitility), nunca `Thread.sleep`;
- isolar dados entre testes e evitar dependência de ordem;
- capturar logs ao falhar e encerrar containers automaticamente;
- não reutilizar container como requisito de CI; reuso local é apenas otimização.

O smoke test acima valida a configuração inicial. O próximo teste deve provar uma migration, constraint ou fluxo de mensagem concreto.
