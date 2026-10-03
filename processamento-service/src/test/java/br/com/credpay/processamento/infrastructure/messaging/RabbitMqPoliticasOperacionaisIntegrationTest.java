package br.com.credpay.processamento.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.credpay.testing.InputTopologyTestApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@SpringBootTest(classes = InputTopologyTestApplication.class, properties = {
        "credpay.processamento.consumer.topology.enabled=true",
        "credpay.processamento.consumer.listener.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class RabbitMqPoliticasOperacionaisIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ARTEFATO = "/tmp/processamento-policies.json";

    @Container
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(
            DockerImageName.parse(
                            "rabbitmq:4.3.5-management-alpine@sha256:b3b8b7f95f5382a19f9ea33540e604f30aad081d37ad9aba72255135765373a1")
                    .asCompatibleSubstituteFor("rabbitmq"))
            .withCopyFileToContainer(MountableFile.forHostPath(
                    Path.of("../infra/rabbitmq/processamento-policies.json").toAbsolutePath()), ARTEFATO);

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
        var outraFila = "credpay.processing.unrelated.v1";
        admin.declareQueue(QueueBuilder.durable(outraFila).quorum().build());
        try {
            // Operational configuration: import the exact versioned artifact, not a Java copy.
            importar();
            conferirPreCondicoes();
            assertThat(fila(outraFila).path("effective_policy_definition").size()).isZero();

            rabbit.convertAndSend("", RabbitMqEntradaConfiguration.ENTRADA, "sentinela-entrada");
            rabbit.convertAndSend("", RabbitMqEntradaConfiguration.DLQ, "sentinela-dlq");
            aguardarSentinelasResidentes();
            importar();
            conferirPreCondicoes();

            var entrada = rabbit.receive(RabbitMqEntradaConfiguration.ENTRADA, 5_000);
            var dlq = rabbit.receive(RabbitMqEntradaConfiguration.DLQ, 5_000);
            assertThat(entrada).isNotNull();
            assertThat(dlq).isNotNull();
            assertThat(new String(entrada.getBody(), java.nio.charset.StandardCharsets.UTF_8))
                    .isEqualTo("sentinela-entrada");
            assertThat(new String(dlq.getBody(), java.nio.charset.StandardCharsets.UTF_8))
                    .isEqualTo("sentinela-dlq");
            assertThat(rabbit.receive(RabbitMqEntradaConfiguration.ENTRADA, 200)).isNull();
            assertThat(rabbit.receive(RabbitMqEntradaConfiguration.DLQ, 200)).isNull();
        } finally {
            admin.deleteQueue(outraFila);
        }
    }

    private void importar() throws Exception {
        var resultado = RABBITMQ.execInContainer("rabbitmqctl", "import_definitions", ARTEFATO);
        assertThat(resultado.getExitCode()).as("importacao do artefato operacional").isZero();
    }

    private void aguardarSentinelasResidentes() throws Exception {
        var prazo = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        do {
            var filas = consultar("list_queues", "-p", "/", "name", "messages_ready");
            if (porNome(filas, RabbitMqEntradaConfiguration.ENTRADA).path("messages_ready").asInt() == 1
                    && porNome(filas, RabbitMqEntradaConfiguration.DLQ).path("messages_ready").asInt() == 1) {
                return;
            }
            java.util.concurrent.TimeUnit.MILLISECONDS.sleep(100);
        } while (System.nanoTime() < prazo);
        throw new AssertionError("sentinelas nao residentes nas duas filas antes da reaplicacao");
    }

    private void conferirPreCondicoes() throws Exception {
        var flags = consultar("list_feature_flags", "name", "state");
        assertThat(porNome(flags, "stream_queue").path("state").asText()).isEqualTo("enabled");
        conferirFila(RabbitMqEntradaConfiguration.ENTRADA, "credpay-processing-input", """
                {"dead-letter-strategy":"at-least-once","overflow":"reject-publish",
                 "max-length":10000,"dead-letter-exchange":"credpay.processamento.dlx.v1",
                 "dead-letter-routing-key":"transacao.criada.dlq.v1"}
                """);
        conferirFila(RabbitMqEntradaConfiguration.DLQ, "credpay-processing-dlq-limit", """
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
        assertThat(fila.path("arguments")).isEqualTo(JSON.readTree("{\"x-queue-type\":\"quorum\"}"));
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
