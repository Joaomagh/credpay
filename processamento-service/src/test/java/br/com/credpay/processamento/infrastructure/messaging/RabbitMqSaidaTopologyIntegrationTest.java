package br.com.credpay.processamento.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import br.com.credpay.processamento.application.EventoSaidaPendente;
import br.com.credpay.processamento.application.PublicadorEventoSaida;

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

@SpringBootTest(classes = RabbitMqSaidaTopologyIntegrationTest.TestApplication.class)
@Testcontainers
class RabbitMqSaidaTopologyIntegrationTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class
    })
    @ComponentScan(basePackages = "br.com.credpay.processamento.infrastructure.messaging")
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

    @Autowired private DirectExchange exchange;
    @Autowired private AmqpAdmin admin;
    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired(required = false) private PublicadorEventoSaida publicador;

    @Test
    void publicador_deveEnviarPayloadEPropriedades_quandoConfirmadoERoteado() {
        var eventId = UUID.randomUUID();
        var transactionId = UUID.randomUUID();
        var payload = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"TransacaoProcessada\"}";
        var evento = new EventoSaidaPendente(eventId, transactionId,
                "TransacaoProcessada", 1, payload, Instant.parse("2026-10-01T12:00:00Z"));
        var filaTeste = new AnonymousQueue();
        admin.declareQueue(filaTeste);
        try {
            admin.declareBinding(BindingBuilder.bind(filaTeste)
                    .to(exchange).with(RabbitMqSaidaConfiguration.TRANSACAO_PROCESSADA_ROUTING_KEY));

            assertThat(publicador).isNotNull();
            assertThat(publicador.publicar(evento)).isTrue();

            var recebida = rabbitTemplate.receive(filaTeste.getName(), 5_000);
            assertThat(recebida).isNotNull();
            assertThat(new String(recebida.getBody(), StandardCharsets.UTF_8)).isEqualTo(payload);
            assertThat(recebida.getMessageProperties().getReceivedDeliveryMode())
                    .isEqualTo(MessageDeliveryMode.PERSISTENT);
            assertThat(recebida.getMessageProperties().getContentType()).isEqualTo("application/json");
            assertThat(recebida.getMessageProperties().getContentEncoding()).isEqualTo("UTF-8");
            assertThat(recebida.getMessageProperties().getMessageId()).isEqualTo(eventId.toString());
            assertThat(recebida.getMessageProperties().getType()).isEqualTo("TransacaoProcessada");
            assertThat(recebida.getMessageProperties().getCorrelationId()).isEqualTo(transactionId.toString());
        } finally {
            admin.deleteQueue(filaTeste.getName());
        }
    }

    @Test
    void topologia_deveRotearMensagemPersistente_pelaExchangeDeSaida() {
        assertThat(RabbitMqSaidaConfiguration.TRANSACAO_PROCESSADA_ROUTING_KEY)
                .isEqualTo("transacao.processada.v1");
        assertThat(exchange.getName()).isEqualTo("credpay.processamento.v1");
        assertThat(exchange.isDurable()).isTrue();
        assertThat(exchange.isAutoDelete()).isFalse();
        var filaTeste = new AnonymousQueue();
        admin.declareQueue(filaTeste);
        try {
            admin.declareBinding(BindingBuilder.bind(filaTeste)
                    .to(exchange).with("transacao.processada.v1"));
            rabbitTemplate.convertAndSend(exchange.getName(), "transacao.processada.v1",
                    "{\"eventType\":\"TransacaoProcessada\"}", mensagem -> {
                        mensagem.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        return mensagem;
                    });

            var recebida = rabbitTemplate.receive(filaTeste.getName(), 5_000);
            assertThat(recebida).isNotNull();
            assertThat(recebida.getMessageProperties().getReceivedDeliveryMode())
                    .isEqualTo(MessageDeliveryMode.PERSISTENT);
            assertThat(new String(recebida.getBody(), StandardCharsets.UTF_8))
                    .isEqualTo("{\"eventType\":\"TransacaoProcessada\"}");
        } finally {
            admin.deleteQueue(filaTeste.getName());
        }
    }
}
