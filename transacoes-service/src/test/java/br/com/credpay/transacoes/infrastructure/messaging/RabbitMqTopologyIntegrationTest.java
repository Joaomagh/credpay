package br.com.credpay.transacoes.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import br.com.credpay.transacoes.application.EventoOutbox;
import br.com.credpay.transacoes.application.PublicadorEvento;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(classes = RabbitMqTopologyIntegrationTest.TestApplication.class)
@Testcontainers
class RabbitMqTopologyIntegrationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class
    })
    @ComponentScan(basePackages = "br.com.credpay.transacoes.infrastructure.messaging")
    static class TestApplication {
    }

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

    @Autowired
    private DirectExchange transacaoEventosExchange;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private PublicadorEvento publicadorEvento;

    @Test
    void topologia_deveRotearEventoPersistentePelaExchangeDuravel() {
        assertThat(transacaoEventosExchange.getName())
                .isEqualTo(RabbitMqConfiguration.TRANSACAO_EVENTOS_EXCHANGE);
        assertThat(transacaoEventosExchange.isDurable()).isTrue();
        assertThat(transacaoEventosExchange.isAutoDelete()).isFalse();

        var filaTeste = new AnonymousQueue();
        amqpAdmin.declareQueue(filaTeste);
        amqpAdmin.declareBinding(BindingBuilder.bind(filaTeste)
                .to(transacaoEventosExchange)
                .with(RabbitMqConfiguration.TRANSACAO_CRIADA_ROUTING_KEY));

        rabbitTemplate.convertAndSend(
                transacaoEventosExchange.getName(),
                RabbitMqConfiguration.TRANSACAO_CRIADA_ROUTING_KEY,
                "{\"eventType\":\"TransacaoCriada\"}",
                mensagem -> {
                    mensagem.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    return mensagem;
                });

        var recebida = rabbitTemplate.receive(filaTeste.getName(), 5_000);
        assertThat(recebida).isNotNull();
        assertThat(recebida.getMessageProperties().getReceivedDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(new String(recebida.getBody(), StandardCharsets.UTF_8))
                .isEqualTo("{\"eventType\":\"TransacaoCriada\"}");
        amqpAdmin.deleteQueue(filaTeste.getName());
    }

    @Test
    void publicador_deveConfirmarERotearContratoCompleto() {
        var filaTeste = new AnonymousQueue();
        amqpAdmin.declareQueue(filaTeste);
        amqpAdmin.declareBinding(BindingBuilder.bind(filaTeste)
                .to(transacaoEventosExchange)
                .with(RabbitMqConfiguration.TRANSACAO_CRIADA_ROUTING_KEY));
        var evento = evento();

        try {
            var confirmado = publicadorEvento.publicar(evento);

            assertThat(confirmado).isTrue();
            var recebida = rabbitTemplate.receive(filaTeste.getName(), 5_000);
            assertThat(recebida).isNotNull();
            assertThat(new String(recebida.getBody(), StandardCharsets.UTF_8))
                    .isEqualTo(evento.payload());
            assertThat(recebida.getMessageProperties().getContentType())
                    .isEqualTo("application/json");
            assertThat(recebida.getMessageProperties().getMessageId())
                    .isEqualTo(evento.eventId().toString());
            assertThat(recebida.getMessageProperties().getType())
                    .isEqualTo(evento.eventType());
            assertThat(recebida.getMessageProperties().getCorrelationId())
                    .isEqualTo(evento.aggregateId().toString());
            assertThat(recebida.getMessageProperties().getReceivedDeliveryMode())
                    .isEqualTo(MessageDeliveryMode.PERSISTENT);
        } finally {
            amqpAdmin.deleteQueue(filaTeste.getName());
        }
    }

    @Test
    void publicador_deveFalhar_quandoExchangeNaoTiverRota() {
        assertThat(publicadorEvento.publicar(evento())).isFalse();
    }

    private EventoOutbox evento() {
        return new EventoOutbox(
                UUID.fromString("6dc8d48d-5b20-4ee9-ac7e-832e421121aa"),
                UUID.fromString("7b8b61c2-9f63-4d74-9f8d-89cb52de0ed9"),
                "TransacaoCriada",
                1,
                "{\"eventType\":\"TransacaoCriada\"}",
                Instant.parse("2026-09-17T12:00:00Z"));
    }
}
