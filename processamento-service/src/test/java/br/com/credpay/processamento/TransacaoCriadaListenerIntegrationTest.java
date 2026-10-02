package br.com.credpay.processamento;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.credpay.processamento.application.EventoSaidaPendente;
import br.com.credpay.processamento.application.OutboxProcessamentoRepository;
import br.com.credpay.processamento.support.ProducerExchangeFixture;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = {
        "credpay.processamento.limites.BRL=100.00",
        "credpay.processamento.consumer.topology.enabled=true",
        "credpay.processamento.consumer.listener.enabled=true"
})
@Import({ProducerExchangeFixture.class, TransacaoCriadaListenerIntegrationTest.PausaOutboxConfiguration.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class TransacaoCriadaListenerIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            "postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0")
            .withDatabaseName("credpay_processamento_listener_test")
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

    @Autowired private RabbitTemplate rabbitTemplate;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PausaAntesDoCommit pausa;

    @Test
    void deveManterEntregaSemAckAteResultadoEOutboxConfirmarem() throws Exception {
        var eventId = UUID.randomUUID();
        var transactionId = UUID.randomUUID();
        pausa.armar();

        rabbitTemplate.send("credpay.transacoes.v1", "transacao.criada.v1",
                mensagem(eventId, transactionId));

        try {
            assertThat(pausa.aguardarInsercao(10, TimeUnit.SECONDS)).isTrue();
            assertThat(quantidadeNoBanco("processamentos", "event_id", eventId)).isZero();
            assertThat(quantidadeNoBanco("outbox_eventos", "aggregate_id", transactionId)).isZero();
            aguardarEstadoFila("messages_unacknowledged", 1, 20);
        } finally {
            pausa.liberar();
        }

        var prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (quantidadeNoBanco("outbox_eventos", "aggregate_id", transactionId) == 0
                && System.nanoTime() < prazo) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
        assertThat(quantidadeNoBanco("processamentos", "event_id", eventId)).isEqualTo(1);
        assertThat(quantidadeNoBanco("outbox_eventos", "aggregate_id", transactionId)).isEqualTo(1);
        aguardarEstadoFila("messages", 0, 10);
    }

    private int quantidadeNoBanco(String tabela, String coluna, UUID id) {
        return jdbc.queryForObject("select count(*) from " + tabela + " where " + coluna + " = ?",
                Integer.class, id);
    }

    private String estadoFila(String campo) throws Exception {
        var resultado = RABBITMQ.execInContainer("rabbitmqctl", "list_queues", "name", campo);
        assertThat(resultado.getExitCode()).isZero();
        return resultado.getStdout();
    }

    private void aguardarEstadoFila(String campo, int esperado, int segundos) throws Exception {
        var linhaEsperada = "credpay.processamento.transacao-criada.v1\t" + esperado;
        var prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(segundos);
        String estado;
        do {
            estado = estadoFila(campo);
            if (estado.contains(linhaEsperada)) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(200);
        } while (System.nanoTime() < prazo);
        assertThat(estado).contains(linhaEsperada);
    }

    private Message mensagem(UUID eventId, UUID transactionId) {
        var payload = """
                {"eventId":"%s","eventType":"TransacaoCriada","eventVersion":1,
                 "occurredAt":"%s","correlationId":"%s",
                 "data":{"transactionId":"%s","amount":"10.25","currency":"BRL","status":"PENDENTE"}}
                """.formatted(eventId, Instant.parse("2026-10-02T12:00:00Z"),
                transactionId, transactionId);
        var propriedades = new MessageProperties();
        propriedades.setMessageId(eventId.toString());
        propriedades.setType("TransacaoCriada");
        propriedades.setCorrelationId(transactionId.toString());
        return new Message(payload.getBytes(StandardCharsets.UTF_8), propriedades);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PausaOutboxConfiguration {
        @Bean
        @Primary
        PausaAntesDoCommit outboxComPausa(
                @Qualifier("outboxProcessamentoJdbcRepository") OutboxProcessamentoRepository delegate) {
            return new PausaAntesDoCommit(delegate);
        }
    }

    static class PausaAntesDoCommit implements OutboxProcessamentoRepository {
        private final OutboxProcessamentoRepository delegate;
        private volatile CountDownLatch inserida;
        private volatile CountDownLatch liberada;

        PausaAntesDoCommit(OutboxProcessamentoRepository delegate) {
            this.delegate = delegate;
        }

        void armar() {
            inserida = new CountDownLatch(1);
            liberada = new CountDownLatch(1);
        }

        boolean aguardarInsercao(long tempo, TimeUnit unidade) throws InterruptedException {
            return inserida.await(tempo, unidade);
        }

        void liberar() {
            liberada.countDown();
        }

        @Override
        public void adicionar(EventoSaidaPendente evento) {
            delegate.adicionar(evento);
            inserida.countDown();
            try {
                if (!liberada.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("espera de commit excedida");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("espera de commit interrompida", exception);
            }
        }

        @Override
        public java.util.Optional<EventoSaidaPendente> buscarPorEventId(UUID eventId) {
            return delegate.buscarPorEventId(eventId);
        }

        @Override
        public List<EventoSaidaPendente> buscarPendentes(int limite) {
            return delegate.buscarPendentes(limite);
        }

        @Override
        public void marcarPublicado(UUID eventId, Instant publicadoEm) {
            delegate.marcarPublicado(eventId, publicadoEm);
        }
    }
}
