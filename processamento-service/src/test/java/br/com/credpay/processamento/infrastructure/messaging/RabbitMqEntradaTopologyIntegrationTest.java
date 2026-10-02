package br.com.credpay.processamento.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.credpay.testing.InputTopologyTestApplication;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
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
    @Autowired @Qualifier("producerExchangeFixture") private DirectExchange producerExchange;
    @Autowired @Qualifier("transacaoCriadaBinding") private Binding entradaBinding;
    @Autowired @Qualifier("transacaoCriadaDlqBinding") private Binding dlqBinding;

    @BeforeEach
    void configurarPoliticaDeDeadLetter() throws Exception {
        assertThat(rabbitTemplate.getConnectionFactory()).isInstanceOf(CachingConnectionFactory.class);
        assertThat(((CachingConnectionFactory) rabbitTemplate.getConnectionFactory()).getPort())
                .isEqualTo(RABBITMQ.getAmqpPort());
        var politica = "{\"dead-letter-strategy\":\"at-least-once\","
                + "\"overflow\":\"reject-publish\",\"max-length\":10000,"
                + "\"dead-letter-exchange\":\"credpay.processamento.dlx.v1\","
                + "\"dead-letter-routing-key\":\"transacao.criada.dlq.v1\"}";
        var resultado = RABBITMQ.execInContainer("rabbitmqctl", "set_policy", "--apply-to", "quorum_queues",
                "credpay-processing-input", "^credpay[.]processamento[.]transacao-criada[.]v1$", politica);
        assertThat(resultado.getExitCode()).isZero();
        var politicaDlq = RABBITMQ.execInContainer("rabbitmqctl", "set_policy", "--apply-to", "quorum_queues",
                "credpay-processing-dlq-limit", "^credpay[.]processamento[.]transacao-criada[.]dlq[.]v1$",
                "{\"max-length\":1,\"overflow\":\"reject-publish\"}");
        assertThat(politicaDlq.getExitCode()).isZero();
        admin.declareExchange(producerExchange);
        admin.declareExchange(dlx);
        admin.declareQueue(entrada);
        admin.declareQueue(dlq);
        admin.declareBinding(entradaBinding);
        admin.declareBinding(dlqBinding);
    }

    @Test
    void topologia_deveDeclararFilasQuorumEDeadLetteringLimitado() throws Exception {
        assertThat(entrada).isNotNull();
        assertThat(entrada.getName()).isEqualTo("credpay.processamento.transacao-criada.v1");
        assertThat(entrada.isDurable()).isTrue();
        assertThat(entrada.getArguments()).containsEntry("x-queue-type", "quorum");
        assertThat(admin.getQueueProperties(entrada.getName())).isNotNull();
        var politicaEfetiva = RABBITMQ.execInContainer("rabbitmqctl", "list_queues", "name",
                "effective_policy_definition");
        assertThat(politicaEfetiva.getStdout())
                .contains("dead-letter-strategy", "at-least-once", "reject-publish", "max-length", "10000");
        assertThat(dlq).isNotNull();
        assertThat(dlq.getName()).isEqualTo("credpay.processamento.transacao-criada.dlq.v1");
        assertThat(dlq.isDurable()).isTrue();
        assertThat(dlq.getArguments()).containsEntry("x-queue-type", "quorum");
        assertThat(dlx).isNotNull();
        assertThat(dlx.getName()).isEqualTo("credpay.processamento.dlx.v1");
        assertThat(dlx.isDurable()).isTrue();
    }

    @Test
    void bindingDeveReceberEventoPelaExchangeDoProdutor() {
        rabbitTemplate.convertAndSend(producerExchange.getName(), "transacao.criada.v1", "entrada-de-teste");

        var recebida = rabbitTemplate.receive(entrada.getName(), 5_000);
        assertThat(recebida).isNotNull();
        assertThat(new String(recebida.getBody(), StandardCharsets.UTF_8)).isEqualTo("entrada-de-teste");
    }

    @Test
    void deadLetterDeveAguardarDlqCheiaEEntregarAposLiberarCapacidade() throws Exception {
        rabbitTemplate.convertAndSend("", dlq.getName(), "ocupante");
        aguardarDlqCheia();
        var payload = "evento-de-teste";
        rabbitTemplate.convertAndSend("", entrada.getName(), payload);
        rejeitarDaEntrada();
        var ocupante = rabbitTemplate.receive(dlq.getName(), 5_000);
        assertThat(ocupante).isNotNull();
        assertThat(new String(ocupante.getBody(), StandardCharsets.UTF_8)).isEqualTo("ocupante");

        var recebida = rabbitTemplate.receive(dlq.getName(), 15_000);
        assertThat(recebida).isNotNull();
        assertThat(new String(recebida.getBody(), StandardCharsets.UTF_8)).isEqualTo(payload);
    }

    @Test
    void deadLetterDeveAguardarBindingERetomarAposRestaurarRota() throws Exception {
        var payload = "evento-sem-rota";
        admin.removeBinding(dlqBinding);
        try {
            assertThat(bindingsDoBroker()).doesNotContain(dlx.getName() + "\texchange\t" + dlq.getName());
            rabbitTemplate.convertAndSend("", entrada.getName(), payload);
            rejeitarDaEntrada();

            assertThat(rabbitTemplate.receive(dlq.getName(), 2_000)).isNull();
            assertThat(bindingsDoBroker()).doesNotContain(dlx.getName() + "\texchange\t" + dlq.getName());
            var filas = RABBITMQ.execInContainer("rabbitmqctl", "list_queues", "name", "messages");
            assertThat(filas.getExitCode()).isZero();
            assertThat(filas.getStdout()).contains(entrada.getName() + "\t1");
        } finally {
            admin.declareBinding(dlqBinding);
        }

        // O consumidor interno de dead-lettering pode aguardar até três minutos para repetir a rota.
        var recebida = rabbitTemplate.receive(dlq.getName(), 210_000);
        assertThat(recebida).isNotNull();
        assertThat(new String(recebida.getBody(), StandardCharsets.UTF_8)).isEqualTo(payload);
    }

    private String bindingsDoBroker() throws Exception {
        var resultado = RABBITMQ.execInContainer("rabbitmqctl", "list_bindings");
        assertThat(resultado.getExitCode()).isZero();
        return resultado.getStdout();
    }

    private void rejeitarDaEntrada() {
        rabbitTemplate.execute(channel -> {
            var prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            var entrega = channel.basicGet(entrada.getName(), false);
            while (entrega == null && System.nanoTime() < prazo) {
                TimeUnit.MILLISECONDS.sleep(50);
                entrega = channel.basicGet(entrada.getName(), false);
            }
            assertThat(entrega).isNotNull();
            channel.basicReject(entrega.getEnvelope().getDeliveryTag(), false);
            return null;
        });
    }

    private void aguardarDlqCheia() throws InterruptedException {
        var prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        Object quantidade;
        do {
            quantidade = admin.getQueueProperties(dlq.getName()).get("QUEUE_MESSAGE_COUNT");
            if (Integer.valueOf(1).equals(quantidade)) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        } while (System.nanoTime() < prazo);
        assertThat(quantidade).isEqualTo(1);
    }
}
