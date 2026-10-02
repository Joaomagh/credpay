package br.com.credpay.processamento.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.credpay.processamento.support.InputTopologyTestApplication;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(classes = InputTopologyTestApplication.class,
        properties = "credpay.processamento.consumer.topology.enabled=true")
@Testcontainers
class RabbitMqEntradaTopologyIntegrationTest {

    @Container
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(
            DockerImageName.parse(
                            "rabbitmq:4.3.5-management-alpine@sha256:b3b8b7f95f5382a19f9ea33540e604f30aad081d37ad9aba72255135765373a1")
                    .asCompatibleSubstituteFor("rabbitmq"));

    @DynamicPropertySource
    static void configurarRabbitMq(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
    }

    @Autowired(required = false)
    @Qualifier("transacaoCriadaQueue")
    private Queue entrada;

    @Autowired(required = false)
    @Qualifier("transacaoCriadaDlq")
    private Queue dlq;

    @Autowired(required = false)
    @Qualifier("processamentoDeadLetterExchange")
    private DirectExchange dlx;

    @Autowired private AmqpAdmin admin;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired @Qualifier("transacaoCriadaDlqBinding") private Binding dlqBinding;

    @Test
    void topologia_deveDeclararFilasQuorumEDeadLetteringLimitado() {
        assertThat(entrada).isNotNull();
        assertThat(entrada.getName()).isEqualTo("credpay.processamento.transacao-criada.v1");
        assertThat(entrada.isDurable()).isTrue();
        assertThat(entrada.getArguments())
                .containsEntry("x-queue-type", "quorum")
                .containsEntry("x-dead-letter-strategy", "at-least-once")
                .containsEntry("x-overflow", "reject-publish")
                .containsEntry("x-max-length", 10_000)
                .containsEntry("x-dead-letter-exchange", "credpay.processamento.dlx.v1")
                .containsEntry("x-dead-letter-routing-key", "transacao.criada.dlq.v1");
        assertThat(dlq).isNotNull();
        assertThat(dlq.getName()).isEqualTo("credpay.processamento.transacao-criada.dlq.v1");
        assertThat(dlq.isDurable()).isTrue();
        assertThat(dlq.getArguments()).containsEntry("x-queue-type", "quorum");
        assertThat(dlx).isNotNull();
        assertThat(dlx.getName()).isEqualTo("credpay.processamento.dlx.v1");
        assertThat(dlx.isDurable()).isTrue();
    }

    @Test
    void deadLetterDeveAguardarDestinoEEntregarAposRestaurarBinding() throws Exception {
        rabbitTemplate.convertAndSend("credpay.transacoes.v1", "transacao.criada.v1", "rota-direta");
        rabbitTemplate.execute(channel -> {
            var entrega = channel.basicGet(entrada.getName(), false);
            assertThat(entrega).isNotNull();
            channel.basicReject(entrega.getEnvelope().getDeliveryTag(), false);
            return null;
        });
        var direta = rabbitTemplate.receive(dlq.getName(), 5_000);
        assertThat(direta).isNotNull();
        assertThat(new String(direta.getBody(), StandardCharsets.UTF_8)).isEqualTo("rota-direta");

        var payload = "evento-de-teste";
        admin.removeBinding(dlqBinding);
        try {
            rabbitTemplate.convertAndSend("credpay.transacoes.v1", "transacao.criada.v1", payload);
            rabbitTemplate.execute(channel -> {
                var entrega = channel.basicGet(entrada.getName(), false);
                assertThat(entrega).isNotNull();
                channel.basicReject(entrega.getEnvelope().getDeliveryTag(), false);
                return null;
            });
            assertThat(rabbitTemplate.receive(dlq.getName(), 500)).isNull();
            var semRota = RABBITMQ.execInContainer("rabbitmqctl", "list_queues", "name", "messages",
                    "messages_ready", "messages_unacknowledged");
            System.out.println("Sem rota DLQ: " + semRota.getStdout());
        } finally {
            admin.declareBinding(dlqBinding);
        }

        var flags = RABBITMQ.execInContainer("rabbitmqctl", "list_feature_flags");
        System.out.println("Feature flags RabbitMQ: " + flags.getStdout());
        var bindings = RABBITMQ.execInContainer("rabbitmqctl", "list_bindings");
        System.out.println("Bindings RabbitMQ: " + bindings.getStdout());
        var estado = RABBITMQ.execInContainer("rabbitmqctl", "list_queues", "name", "arguments",
                "messages_ready", "messages_unacknowledged");
        System.out.println("Diagnóstico RabbitMQ: " + estado.getStdout());
        var recebida = rabbitTemplate.receive(dlq.getName(), 5_000);
        assertThat(recebida).isNotNull();
        assertThat(new String(recebida.getBody(), StandardCharsets.UTF_8)).isEqualTo(payload);
    }
}
