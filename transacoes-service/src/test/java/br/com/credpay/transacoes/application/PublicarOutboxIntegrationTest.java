package br.com.credpay.transacoes.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Currency;
import java.util.UUID;

import br.com.credpay.transacoes.TransacoesServiceApplication;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
        classes = TransacoesServiceApplication.class,
        properties = "credpay.outbox.publisher.enabled=false")
@Testcontainers
class PublicarOutboxIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            "postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0")
            .withDatabaseName("credpay_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(
            DockerImageName.parse(
                            "rabbitmq:4.3.5-management-alpine@sha256:b3b8b7f95f5382a19f9ea33540e604f30aad081d37ad9aba72255135765373a1")
                    .asCompatibleSubstituteFor("rabbitmq"));

    @DynamicPropertySource
    static void configurarInfraestrutura(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
    }

    @Autowired
    private CriarTransacao criarTransacao;

    @Autowired
    private PublicarOutboxService publicarOutbox;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private DirectExchange transacaoEventosExchange;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void limparDados() {
        jdbcTemplate.update("DELETE FROM outbox_eventos");
        jdbcTemplate.update("DELETE FROM idempotencias_transacao");
        jdbcTemplate.update("DELETE FROM transacoes");
    }

    @Test
    void publicarLote_deveEntregarEventoEMarcarOutbox_quandoCriacaoEstiverConfirmada() throws Exception {
        var fila = new Queue("credpay.test." + UUID.randomUUID(), false, true, false);
        amqpAdmin.declareQueue(fila);
        amqpAdmin.declareBinding(BindingBuilder.bind(fila)
                .to(transacaoEventosExchange)
                .with("transacao.criada.v1"));

        try {
            var transacao = criarTransacao.executar(
                    UUID.randomUUID(), new BigDecimal("123.450"), Currency.getInstance("BRL"));
            var eventId = jdbcTemplate.queryForObject(
                    "SELECT event_id FROM outbox_eventos WHERE aggregate_id = ?",
                    UUID.class, transacao.id());
            var payloadPersistido = jdbcTemplate.queryForObject(
                    "SELECT payload::text FROM outbox_eventos WHERE event_id = ?",
                    String.class, eventId);
            assertThat(publicadoEm(eventId)).isNull();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM transacoes WHERE id = ?", String.class, transacao.id()))
                    .isEqualTo("PENDENTE");

            assertThat(publicarOutbox.publicarLote()).isEqualTo(1);

            var mensagem = rabbitTemplate.receive(fila.getName(), 5_000);
            assertThat(mensagem).isNotNull();
            var envelope = objectMapper.readTree(mensagem.getBody());
            assertThat(envelope).isEqualTo(objectMapper.readTree(payloadPersistido));
            assertThat(envelope.path("eventId").asText()).isEqualTo(eventId.toString());
            assertThat(envelope.path("eventType").asText()).isEqualTo("TransacaoCriada");
            assertThat(envelope.path("eventVersion").asInt()).isEqualTo(1);
            assertThat(envelope.path("correlationId").asText()).isEqualTo(transacao.id().toString());
            assertThat(envelope.path("data").path("transactionId").asText())
                    .isEqualTo(transacao.id().toString());
            assertThat(envelope.path("data").path("amount").asText()).isEqualTo("123.450");
            assertThat(envelope.path("data").path("currency").asText()).isEqualTo("BRL");
            assertThat(envelope.path("data").path("status").asText()).isEqualTo("PENDENTE");
            var propriedades = mensagem.getMessageProperties();
            assertThat(propriedades.getMessageId()).isEqualTo(eventId.toString());
            assertThat(propriedades.getCorrelationId()).isEqualTo(transacao.id().toString());
            assertThat(propriedades.getType()).isEqualTo("TransacaoCriada");
            assertThat(propriedades.getContentType()).isEqualTo("application/json");
            assertThat(propriedades.getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
            var instantePublicado = publicadoEm(eventId);
            assertThat(instantePublicado).isNotNull();

            assertThat(publicarOutbox.publicarLote()).isZero();
            assertThat(publicadoEm(eventId)).isEqualTo(instantePublicado);
            assertThat(rabbitTemplate.receive(fila.getName(), 200)).isNull();
        } finally {
            amqpAdmin.deleteQueue(fila.getName());
        }
    }

    @Test
    void publicarLote_deveRecuperarMesmoEvento_quandoRotaForCriadaAposFalha() throws Exception {
        var fila = new Queue("credpay.test." + UUID.randomUUID(), false, true, false);
        amqpAdmin.declareQueue(fila);

        try {
            var transacao = criarTransacao.executar(
                    UUID.randomUUID(), new BigDecimal("10.00"), Currency.getInstance("BRL"));
            var eventId = jdbcTemplate.queryForObject(
                    "SELECT event_id FROM outbox_eventos WHERE aggregate_id = ?",
                    UUID.class, transacao.id());
            var payloadOriginal = jdbcTemplate.queryForObject(
                    "SELECT payload::text FROM outbox_eventos WHERE event_id = ?",
                    String.class, eventId);

            assertThat(publicarOutbox.publicarLote()).isZero();

            assertThat(publicadoEm(eventId)).isNull();
            assertThat(rabbitTemplate.receive(fila.getName(), 200)).isNull();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM outbox_eventos WHERE aggregate_id = ?",
                    Integer.class, transacao.id())).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT payload::text FROM outbox_eventos WHERE event_id = ?",
                    String.class, eventId)).isEqualTo(payloadOriginal);

            amqpAdmin.declareBinding(BindingBuilder.bind(fila)
                    .to(transacaoEventosExchange)
                    .with("transacao.criada.v1"));

            assertThat(publicarOutbox.publicarLote()).isEqualTo(1);

            var mensagem = rabbitTemplate.receive(fila.getName(), 5_000);
            assertThat(mensagem).isNotNull();
            assertThat(mensagem.getMessageProperties().getMessageId()).isEqualTo(eventId.toString());
            assertThat(objectMapper.readTree(mensagem.getBody()))
                    .isEqualTo(objectMapper.readTree(payloadOriginal));
            assertThat(publicadoEm(eventId)).isNotNull();
            assertThat(publicarOutbox.publicarLote()).isZero();
            assertThat(rabbitTemplate.receive(fila.getName(), 200)).isNull();
        } finally {
            amqpAdmin.deleteQueue(fila.getName());
        }
    }

    private Timestamp publicadoEm(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT published_at FROM outbox_eventos WHERE event_id = ?",
                (resultado, linha) -> resultado.getTimestamp("published_at"),
                eventId);
    }
}
