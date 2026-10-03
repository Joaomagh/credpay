package br.com.credpay.processamento.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.credpay.testing.InputTopologyTestApplication;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
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
        var propriedades = new MessageProperties();
        propriedades.setMessageId(UUID.randomUUID().toString());
        var mensagem = new Message("evento-de-teste".getBytes(StandardCharsets.UTF_8), propriedades);
        try {
            // Quorum reject-publish permits overshoot: this pinned version exceeds max-length=1 at 2.
            rabbitTemplate.convertAndSend("", dlq.getName(), "ocupante-1");
            rabbitTemplate.convertAndSend("", dlq.getName(), "ocupante-2");
            aguardarOcupacaoDlq(2);
            rabbitTemplate.send("", entrada.getName(), mensagem);
            rejeitarDaEntrada();

            aguardarRecusaDoWorker();
            aguardarMensagemRetidaNaOrigem();
            aguardarOcupacaoDlq(2);
            for (var numero = 1; numero <= 2; numero++) {
                var ocupante = rabbitTemplate.receive(dlq.getName(), 5_000);
                assertThat(ocupante).isNotNull();
                assertThat(new String(ocupante.getBody(), StandardCharsets.UTF_8))
                        .isEqualTo("ocupante-" + numero);
            }

            // Recovery is driven by the broker's confirm timeout, not by a fixed sleep in this test.
            var recebida = rabbitTemplate.receive(dlq.getName(), 210_000);
            assertThat(recebida).isNotNull();
            assertThat(Arrays.equals(recebida.getBody(), mensagem.getBody())).isTrue();
            assertThat(recebida.getMessageProperties().getMessageId()).isEqualTo(propriedades.getMessageId());
            assertThat(recebida.getMessageProperties().getHeaders())
                    .containsEntry("x-first-death-reason", "rejected");
            aguardarOrigemVazia();
        } finally {
            // Isolated test queues only. Deleting the source also stops its retained dead-letter worker.
            // BeforeEach recreates both queues/bindings; a failed test cannot leak a delayed delivery.
            try {
                admin.deleteQueue(entrada.getName());
            } finally {
                admin.deleteQueue(dlq.getName());
            }
        }
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
            aguardarMensagemRetidaNaOrigem();
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

    private void aguardarMensagemRetidaNaOrigem() throws Exception {
        var prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        String filas;
        do {
            var resultado = RABBITMQ.execInContainer("rabbitmqctl", "list_queues", "name", "messages");
            assertThat(resultado.getExitCode()).isZero();
            filas = resultado.getStdout();
            if (filas.contains(entrada.getName() + "\t1")) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(200);
        } while (System.nanoTime() < prazo);
        assertThat(filas).contains(entrada.getName() + "\t1");
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

    private void aguardarOcupacaoDlq(int esperado) throws InterruptedException {
        var prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        Object quantidade;
        do {
            quantidade = admin.getQueueProperties(dlq.getName()).get("QUEUE_MESSAGE_COUNT");
            if (Integer.valueOf(esperado).equals(quantidade)) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        } while (System.nanoTime() < prazo);
        assertThat(quantidade).isEqualTo(esperado);
    }

    private void aguardarRecusaDoWorker() throws Exception {
        // sys:get_status uses RabbitMQ's format_status, which omits message delivery bodies.
        // Project only a boolean. Never print the complete status or diagnostic stderr.
        var diagnostico = """
                try
                  Source = {resource, <<"/">>, queue, <<"credpay.processamento.transacao-criada.v1">>},
                  Target = {resource, <<"/">>, queue, <<"credpay.processamento.transacao-criada.dlq.v1">>},
                  Find = fun F(#{queue_ref := Q, pendings := Ps}) when Q =:= Source -> [Ps];
                             F(M) when is_map(M) -> lists:flatmap(F, maps:values(M));
                             F(L) when is_list(L) -> lists:flatmap(F, L);
                             F(T) when is_tuple(T) -> F(tuple_to_list(T));
                             F(_) -> []
                         end,
                  States = lists:flatmap(
                    fun({_, Pid, _, _}) when is_pid(Pid) -> Find(sys:get_status(Pid, 5000));
                       (_) -> []
                    end, supervisor:which_children(rabbit_fifo_dlx_sup)),
                  case States of
                    [Pendings] -> lists:any(
                      fun(P) -> maps:get(publish_count, P) >= 1 andalso
                                lists:member(Target, maps:get(rejected, P))
                      end, maps:values(Pendings));
                    [] -> worker_pending;
                    _ -> diagnostic_error
                  end
                catch _:_ -> diagnostic_error
                end.
                """;
        var prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        do {
            var resultado = RABBITMQ.execInContainer("rabbitmqctl", "--quiet", "eval", diagnostico);
            assertThat(resultado.getExitCode()).as("diagnostico do worker concluiu").isZero();
            var projecao = resultado.getStdout().trim();
            assertThat(projecao.equals("true") || projecao.equals("false") || projecao.equals("worker_pending"))
                    .as("projecao segura compativel com RabbitMQ fixado").isTrue();
            if (projecao.equals("true")) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(200);
        } while (System.nanoTime() < prazo);
        throw new AssertionError("recusa efetiva do worker nao observada; capacidade nao foi liberada pelo cenario");
    }

    private void aguardarOrigemVazia() throws Exception {
        var prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        do {
            var resultado = RABBITMQ.execInContainer("rabbitmqctl", "list_queues", "name", "messages");
            assertThat(resultado.getExitCode()).isZero();
            if (resultado.getStdout().contains(entrada.getName() + "\t0")) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(200);
        } while (System.nanoTime() < prazo);
        throw new AssertionError("origem nao esvaziou apos confirmacao da DLQ");
    }
}
