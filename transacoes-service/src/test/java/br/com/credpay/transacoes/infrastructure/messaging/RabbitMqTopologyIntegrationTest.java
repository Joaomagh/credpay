package br.com.credpay.transacoes.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

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

    @Test
    void topologia_deveRotearEventoPersistentePelaExchangeDuravel() {
        assertThat(transacaoEventosExchange.getName()).isEqualTo("credpay.transacoes.v1");
        assertThat(transacaoEventosExchange.isDurable()).isTrue();
        assertThat(transacaoEventosExchange.isAutoDelete()).isFalse();

        var filaTeste = new AnonymousQueue();
        amqpAdmin.declareQueue(filaTeste);
        amqpAdmin.declareBinding(BindingBuilder.bind(filaTeste)
                .to(transacaoEventosExchange)
                .with("transacao.criada.v1"));

        rabbitTemplate.convertAndSend(
                transacaoEventosExchange.getName(),
                "transacao.criada.v1",
                "{\"eventType\":\"TransacaoCriada\"}",
                mensagem -> {
                    mensagem.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    return mensagem;
                });

        var recebida = rabbitTemplate.receive(filaTeste.getName(), 5_000);
        assertThat(recebida).isNotNull();
        assertThat(recebida.getMessageProperties().getDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(new String(recebida.getBody(), StandardCharsets.UTF_8))
                .isEqualTo("{\"eventType\":\"TransacaoCriada\"}");
    }
}
