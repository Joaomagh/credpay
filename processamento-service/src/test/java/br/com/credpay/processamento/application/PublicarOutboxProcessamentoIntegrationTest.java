package br.com.credpay.processamento.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.actuate.amqp.RabbitHealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(properties = {
        "credpay.processamento.limites.BRL=100.00",
        "credpay.outbox.publisher.enabled=true",
        "credpay.outbox.publisher.interval=PT1H"
})
@Import(PublicarOutboxProcessamentoIntegrationTest.FalhaNaMarcacaoConfiguration.class)
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
    @Autowired private FalhaNaMarcacao falhaNaMarcacao;
    @Autowired(required = false) private RabbitHealthIndicator rabbitHealthIndicator;

    @BeforeEach
    void limparDados() {
        falhaNaMarcacao.desarmar();
        jdbc.update("delete from outbox_eventos");
        jdbc.update("delete from processamentos");
    }

    @Test
    void healthRabbit_deveEstarAtivo_quandoPublicacaoAutomaticaForHabilitada() throws Exception {
        assertThat(rabbitHealthIndicator).isNotNull();
        assertThat(rabbitHealthIndicator.health().getStatus()).isEqualTo(Status.UP);
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

    @Test
    void publicarProximo_deveReenviarMesmoEvento_quandoMarcacaoFalhaAposConfirmacao() throws Exception {
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
            falhaNaMarcacao.armar();

            assertThatThrownBy(() -> publicarOutbox.publicarProximo())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("falha controlada antes de marcar publicado");
            var primeira = rabbitTemplate.receive(fila.getName(), 5_000);
            assertThat(primeira).isNotNull();
            assertThat(primeira.getMessageProperties().getMessageId()).isEqualTo(eventId.toString());
            assertThat(objectMapper.readTree(primeira.getBody()))
                    .isEqualTo(objectMapper.readTree(payload));
            assertThat(publicadoEm(eventId)).isNull();

            falhaNaMarcacao.desarmar();
            assertThat(publicarOutbox.publicarProximo()).isTrue();
            var segunda = rabbitTemplate.receive(fila.getName(), 5_000);
            assertThat(segunda).isNotNull();
            assertThat(segunda.getMessageProperties().getMessageId()).isEqualTo(eventId.toString());
            assertThat(objectMapper.readTree(segunda.getBody()))
                    .isEqualTo(objectMapper.readTree(payload));
            assertThat(publicadoEm(eventId)).isNotNull();
        } finally {
            falhaNaMarcacao.desarmar();
            admin.deleteQueue(fila.getName());
        }
    }

    private Timestamp publicadoEm(UUID eventId) {
        return jdbc.queryForObject("select published_at from outbox_eventos where event_id = ?",
                (resultado, linha) -> resultado.getTimestamp("published_at"), eventId);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FalhaNaMarcacaoConfiguration {
        @Bean @Primary
        FalhaNaMarcacao outboxComFalhaNaMarcacao(
                @Qualifier("outboxProcessamentoJdbcRepository") OutboxProcessamentoRepository delegate) {
            return new FalhaNaMarcacao(delegate);
        }
    }

    static class FalhaNaMarcacao implements OutboxProcessamentoRepository {
        private final OutboxProcessamentoRepository delegate;
        private final AtomicBoolean falhar = new AtomicBoolean();

        FalhaNaMarcacao(OutboxProcessamentoRepository delegate) {
            this.delegate = delegate;
        }

        void armar() {
            falhar.set(true);
        }

        void desarmar() {
            falhar.set(false);
        }

        @Override public void adicionar(EventoSaidaPendente evento) {
            delegate.adicionar(evento);
        }

        @Override public Optional<EventoSaidaPendente> buscarPorEventId(UUID eventId) {
            return delegate.buscarPorEventId(eventId);
        }

        @Override public List<EventoSaidaPendente> buscarPendentes(int limite) {
            return delegate.buscarPendentes(limite);
        }

        @Override public void marcarPublicado(UUID eventId, Instant publicadoEm) {
            if (falhar.get()) {
                throw new IllegalStateException("falha controlada antes de marcar publicado");
            }
            delegate.marcarPublicado(eventId, publicadoEm);
        }
    }
}
