package br.com.credpay.processamento.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
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

@SpringBootTest(properties = "credpay.processamento.limites.BRL=100.00")
@Testcontainers
class PublicarOutboxProcessamentoIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            "postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0")
            .withDatabaseName("credpay_processamento_publicacao_test")
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

    @Autowired private RegistrarProcessamentoService registrar;
    @Autowired(required = false) private PublicarOutboxProcessamento publicarOutbox;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AmqpAdmin admin;
    @Autowired private DirectExchange exchange;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private ObjectMapper objectMapper;

    @BeforeEach
    void limparDados() {
        jdbc.update("delete from outbox_eventos");
        jdbc.update("delete from processamentos");
    }

    @Test
    void publicarProximo_deveEntregarEventoEMarcarPendente_aposConfirmacaoRoteada() throws Exception {
        var fila = new Queue("credpay.test." + UUID.randomUUID(), true, false, false);
        admin.declareQueue(fila);
        try {
            admin.declareBinding(BindingBuilder.bind(fila)
                    .to(exchange).with("transacao.processada.v1"));
            var transacaoId = UUID.randomUUID();
            var resultado = registrar.executar(new TransacaoCriadaRecebida(
                    UUID.randomUUID(), transacaoId, Instant.parse("2026-10-01T12:00:00Z"),
                    transacaoId, new BigDecimal("75.00"), Currency.getInstance("BRL")));
            var eventId = resultado.outputEventId();
            var payload = jdbc.queryForObject(
                    "select payload::text from outbox_eventos where event_id = ?", String.class, eventId);
            assertThat(publicadoEm(eventId)).isNull();

            assertThat(publicarOutbox).isNotNull();
            assertThat(publicarOutbox.publicarProximo()).isTrue();

            var mensagem = rabbitTemplate.receive(fila.getName(), 5_000);
            assertThat(mensagem).isNotNull();
            assertThat(objectMapper.readTree(mensagem.getBody()))
                    .isEqualTo(objectMapper.readTree(payload));
            assertThat(mensagem.getMessageProperties().getMessageId()).isEqualTo(eventId.toString());
            assertThat(publicadoEm(eventId)).isNotNull();
            assertThat(publicarOutbox.publicarProximo()).isFalse();
            assertThat(rabbitTemplate.receive(fila.getName(), 200)).isNull();
        } finally {
            admin.deleteQueue(fila.getName());
        }
    }

    @Test
    void publicarProximo_deveReenviarMesmoEvento_quandoRotaSurgirAposFalha() throws Exception {
        var fila = new Queue("credpay.test." + UUID.randomUUID(), true, false, false);
        admin.declareQueue(fila);
        try {
            var transacaoId = UUID.randomUUID();
            var resultado = registrar.executar(new TransacaoCriadaRecebida(
                    UUID.randomUUID(), transacaoId, Instant.parse("2026-10-01T12:00:00Z"),
                    transacaoId, new BigDecimal("75.00"), Currency.getInstance("BRL")));
            var eventId = resultado.outputEventId();
            var payload = jdbc.queryForObject(
                    "select payload::text from outbox_eventos where event_id = ?", String.class, eventId);

            assertThat(publicarOutbox.publicarProximo()).isFalse();
            assertThat(publicadoEm(eventId)).isNull();
            assertThat(jdbc.queryForObject("select count(*) from outbox_eventos where event_id = ?",
                    Integer.class, eventId)).isEqualTo(1);

            admin.declareBinding(BindingBuilder.bind(fila)
                    .to(exchange).with("transacao.processada.v1"));

            assertThat(publicarOutbox.publicarProximo()).isTrue();
            var mensagem = rabbitTemplate.receive(fila.getName(), 5_000);
            assertThat(mensagem).isNotNull();
            assertThat(mensagem.getMessageProperties().getMessageId()).isEqualTo(eventId.toString());
            assertThat(objectMapper.readTree(mensagem.getBody()))
                    .isEqualTo(objectMapper.readTree(payload));
            assertThat(publicadoEm(eventId)).isNotNull();
        } finally {
            admin.deleteQueue(fila.getName());
        }
    }

    private Timestamp publicadoEm(UUID eventId) {
        return jdbc.queryForObject("select published_at from outbox_eventos where event_id = ?",
                (resultado, linha) -> resultado.getTimestamp("published_at"), eventId);
    }
}
