package br.com.credpay.transacoes.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@SpringBootTest(classes = RabbitMqResultadoPoliticasIntegrationTest.TestApplication.class, properties = {
        "credpay.transacoes.consumer.topology.enabled=true",
        "credpay.transacoes.consumer.listener.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class RabbitMqResultadoPoliticasIntegrationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class, FlywayAutoConfiguration.class})
    @Import(RabbitMqResultadoConfiguration.class)
    static class TestApplication {
        // Only the fixture owns this declaration; production references the producer exchange.
        @Bean
        DirectExchange producerExchangeFixture() {
            return new DirectExchange("credpay.processamento.v1", true, false);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ARTEFATO = "/tmp/transacoes-policies.json";

    @Container
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(
            DockerImageName.parse(
                            "rabbitmq:4.3.5-management-alpine@sha256:b3b8b7f95f5382a19f9ea33540e604f30aad081d37ad9aba72255135765373a1")
                    .asCompatibleSubstituteFor("rabbitmq"))
            .withCopyFileToContainer(MountableFile.forHostPath(
                    Path.of("../infra/rabbitmq/transacoes-policies.json").toAbsolutePath()), ARTEFATO);

    @DynamicPropertySource
    static void configurarRabbitMq(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
    }

    @Autowired private AmqpAdmin admin;
    @Autowired private RabbitTemplate rabbit;

    @Test
    void importar_deveAplicarPoliticasEfetivasEReaplicarSemPerderMensagem() throws Exception {
        var outraFila = "credpay.transacoes.transacao-processada.v2";
        admin.declareQueue(QueueBuilder.durable(outraFila).quorum().build());
        try {
            // Operational configuration: import the exact versioned artifact, not a Java copy.
            importar();
            conferirPreCondicoes();
            assertThat(fila(outraFila).path("effective_policy_definition").size()).isZero();

            rabbit.convertAndSend("", RabbitMqResultadoConfiguration.ENTRADA, "sentinela-entrada");
            rabbit.convertAndSend("", RabbitMqResultadoConfiguration.DLQ, "sentinela-dlq");
            aguardarSentinelasResidentes();
            importar();
            conferirPreCondicoes();

            var entrada = rabbit.receive(RabbitMqResultadoConfiguration.ENTRADA, 5_000);
            var dlq = rabbit.receive(RabbitMqResultadoConfiguration.DLQ, 5_000);
            assertThat(entrada).isNotNull();
            assertThat(dlq).isNotNull();
            assertThat(new String(entrada.getBody(), java.nio.charset.StandardCharsets.UTF_8))
                    .isEqualTo("sentinela-entrada");
            assertThat(new String(dlq.getBody(), java.nio.charset.StandardCharsets.UTF_8))
                    .isEqualTo("sentinela-dlq");
            assertThat(rabbit.receive(RabbitMqResultadoConfiguration.ENTRADA, 200)).isNull();
            assertThat(rabbit.receive(RabbitMqResultadoConfiguration.DLQ, 200)).isNull();
        } finally {
            admin.deleteQueue(outraFila);
        }
    }

    @Test
    void rotear_deveEntregarNasFilasPelosBindingsExatosSemConsumidor() throws Exception {
        assertThat(admin.getQueueProperties(RabbitMqResultadoConfiguration.ENTRADA)).isNotNull();
        importar();
        conferirPreCondicoes();
        rabbit.convertAndSend(RabbitMqResultadoConfiguration.EXCHANGE_PRODUTOR,
                RabbitMqResultadoConfiguration.ROTA_ENTRADA, "sentinela-resultado");
        rabbit.convertAndSend(RabbitMqResultadoConfiguration.DLX,
                RabbitMqResultadoConfiguration.ROTA_DLQ, "sentinela-rota-dlq");
        var entrada = rabbit.receive(RabbitMqResultadoConfiguration.ENTRADA, 5_000);
        var dlq = rabbit.receive(RabbitMqResultadoConfiguration.DLQ, 5_000);
        assertThat(entrada).isNotNull();
        assertThat(dlq).isNotNull();
        assertThat(new String(entrada.getBody(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("sentinela-resultado");
        assertThat(new String(dlq.getBody(), java.nio.charset.StandardCharsets.UTF_8)).isEqualTo("sentinela-rota-dlq");
        assertThat(rabbit.receive(RabbitMqResultadoConfiguration.ENTRADA, 200)).isNull();
        assertThat(rabbit.receive(RabbitMqResultadoConfiguration.DLQ, 200)).isNull();
    }

    private void importar() throws Exception {
        var resultado = RABBITMQ.execInContainer("rabbitmqctl", "import_definitions", ARTEFATO);
        assertThat(resultado.getExitCode()).as("importacao do artefato operacional").isZero();
    }

    private void aguardarSentinelasResidentes() throws Exception {
        var prazo = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        do {
            var filas = consultar("list_queues", "-p", "/", "name", "messages_ready");
            if (porNome(filas, RabbitMqResultadoConfiguration.ENTRADA).path("messages_ready").asInt() == 1
                    && porNome(filas, RabbitMqResultadoConfiguration.DLQ).path("messages_ready").asInt() == 1) {
                return;
            }
            java.util.concurrent.TimeUnit.MILLISECONDS.sleep(100);
        } while (System.nanoTime() < prazo);
        throw new AssertionError("sentinelas nao residentes nas duas filas antes da reaplicacao");
    }

    private void conferirPreCondicoes() throws Exception {
        var flags = consultar("list_feature_flags", "name", "state");
        assertThat(porNome(flags, "stream_queue").path("state").asText()).isEqualTo("enabled");
        conferirFila(RabbitMqResultadoConfiguration.ENTRADA, "credpay-transactions-input", """
                {"dead-letter-strategy":"at-least-once","overflow":"reject-publish",
                 "max-length":10000,"dead-letter-exchange":"credpay.transacoes.dlx.v1",
                 "dead-letter-routing-key":"transacao.processada.dlq.v1"}
                """);
        conferirFila(RabbitMqResultadoConfiguration.DLQ, "credpay-transactions-dlq-limit", """
                {"max-length":1000,"overflow":"reject-publish"}
                """);
        var consumidores = consultar("list_consumers", "-p", "/");
        assertThat(consumidores.size()).as("listener desligado durante preparacao").isZero();
    }

    private void conferirFila(String nome, String politica, String definicao) throws Exception {
        var fila = fila(nome);
        assertThat(fila.path("type").asText()).isEqualTo("quorum");
        assertThat(fila.path("durable").asBoolean()).isTrue();
        assertThat(fila.path("policy").asText()).isEqualTo(politica);
        assertThat(fila.path("operator_policy").asText()).isEmpty();
        // rabbitmqctl exposes the AMQP table as name/type/value triples, not a management API object.
        assertThat(fila.path("arguments")).isEqualTo(JSON.readTree("[[\"x-queue-type\",\"longstr\",\"quorum\"]]"));
        assertThat(fila.path("effective_policy_definition")).isEqualTo(JSON.readTree(definicao));
    }

    private JsonNode fila(String nome) throws Exception {
        return porNome(consultar("list_queues", "-p", "/", "name", "type", "durable", "arguments",
                "policy", "operator_policy", "effective_policy_definition"), nome);
    }

    private JsonNode consultar(String... argumentos) throws Exception {
        var comando = new java.util.ArrayList<String>();
        comando.add("rabbitmqctl");
        comando.add("--quiet");
        comando.add("--formatter=json");
        comando.addAll(java.util.List.of(argumentos));
        var resultado = RABBITMQ.execInContainer(comando.toArray(String[]::new));
        assertThat(resultado.getExitCode()).as("consulta operacional concluiu").isZero();
        return JSON.readTree(resultado.getStdout());
    }

    private JsonNode porNome(JsonNode linhas, String nome) {
        var encontradas = StreamSupport.stream(linhas.spliterator(), false)
                .filter(linha -> nome.equals(linha.path("name").asText())).toList();
        assertThat(encontradas).as("recurso exato %s", nome).hasSize(1);
        return encontradas.getFirst();
    }
}
