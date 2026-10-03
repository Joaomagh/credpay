package br.com.credpay.transacoes.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;

import br.com.credpay.transacoes.application.CriarTransacao;
import br.com.credpay.transacoes.TransacoesServiceApplication;
import br.com.credpay.transacoes.application.TransicaoRecebida;
import br.com.credpay.transacoes.application.TransicaoRepository;
import br.com.credpay.transacoes.domain.StatusTransacao;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

@SpringBootTest(classes = TransacoesServiceApplication.class, properties = {"credpay.transacoes.consumer.topology.enabled=true",
        "credpay.transacoes.consumer.listener.enabled=true", "spring.rabbitmq.listener.simple.auto-startup=false",
        "spring.rabbitmq.listener.simple.acknowledge-mode=NONE", "spring.rabbitmq.listener.simple.concurrency=2",
        "spring.rabbitmq.listener.simple.max-concurrency=3", "spring.rabbitmq.listener.simple.prefetch=3"})
@Import(TransacaoProcessadaListenerIntegrationTest.Fixture.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class TransacaoProcessadaListenerIntegrationTest {

    private static final String ENTRADA = "credpay.transacoes.transacao-processada.v1";
    private static final Instant OCORRIDO = Instant.parse("2026-10-03T12:00:00.123456789Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            "postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0")
            .withDatabaseName("credpay_resultado_listener_test").withUsername("test").withPassword("test");

    @Container
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer(DockerImageName.parse(
            "rabbitmq:4.3.5-management-alpine@sha256:b3b8b7f95f5382a19f9ea33540e604f30aad081d37ad9aba72255135765373a1")
            .asCompatibleSubstituteFor("rabbitmq"))
            .withCopyFileToContainer(MountableFile.forHostPath(
                    Path.of("../infra/rabbitmq/transacoes-policies.json").toAbsolutePath()), "/tmp/transacoes-policies.json");

    @DynamicPropertySource
    static void infraestrutura(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
    }

    @Autowired private CriarTransacao criar;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private RabbitTemplate rabbit;
    @Autowired private AmqpAdmin admin;
    @Autowired private RabbitListenerEndpointRegistry listeners;
    @Autowired private HistoricoComPausa pausa;
    @Autowired private RelogioContado clock;
    @Autowired @Qualifier("transicaoJdbcRepository") private TransicaoRepository historico;

    @BeforeEach
    void prepararAntesDeConsumir() throws Exception {
        assertThat(listeners.getListenerContainers()).as("listener de retorno registrado").hasSize(1);
        assertThat(listeners.getListenerContainers()).allSatisfy(container -> {
            assertThat(container.isRunning()).isFalse();
            assertThat(container).isInstanceOf(SimpleMessageListenerContainer.class);
            var simple = (SimpleMessageListenerContainer) container;
            assertThat(simple.getQueueNames()).containsExactly(ENTRADA);
            assertThat(simple.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.AUTO);
        });
        assertThat(admin.getQueueProperties(ENTRADA)).isNotNull();
        var importacao = RABBITMQ.execInContainer("rabbitmqctl", "import_definitions", "/tmp/transacoes-policies.json");
        assertThat(importacao.getExitCode()).isZero();
        var filas = consultarFilas();
        var fila = porNome(filas, ENTRADA);
        assertThat(fila.path("policy").asText()).isEqualTo("credpay-transactions-input");
        assertThat(fila.path("effective_policy_definition")).isEqualTo(JSON.readTree("""
                {"dead-letter-strategy":"at-least-once","overflow":"reject-publish","max-length":10000,
                 "dead-letter-exchange":"credpay.transacoes.dlx.v1","dead-letter-routing-key":"transacao.processada.dlq.v1"}
                """));
        var dlq = porNome(filas, RabbitMqResultadoConfiguration.DLQ);
        assertThat(dlq.path("policy").asText()).isEqualTo("credpay-transactions-dlq-limit");
        assertThat(dlq.path("effective_policy_definition")).isEqualTo(JSON.readTree(
                "{\"max-length\":1000,\"overflow\":\"reject-publish\"}"));
        var flags = RABBITMQ.execInContainer("rabbitmqctl", "--timeout", "5", "--quiet", "--formatter=json",
                "list_feature_flags", "name", "state");
        assertThat(flags.getExitCode()).isZero();
        assertThat(porNome(JSON.readTree(flags.getStdout()), "stream_queue").path("state").asText()).isEqualTo("enabled");
        listeners.start();
        aguardarConsumidorExato();
    }

    @AfterEach
    void encerrarConsumo() {
        pausa.liberar();
        listeners.stop();
    }

    @ParameterizedTest
    @EnumSource(value = StatusTransacao.class, names = {"APROVADA", "REJEITADA"})
    void receber_deveConfirmarSomenteDepoisDoCommitDoEstadoEHistorico(StatusTransacao status) throws Exception {
        var original = criar.executar(UUID.randomUUID(), new BigDecimal("123.450"), Currency.getInstance("BRL"));
        var causa = jdbc.queryForObject("SELECT event_id FROM outbox_eventos WHERE aggregate_id = ?", UUID.class, original.id());
        var evento = UUID.randomUUID();
        clock.resetar();
        pausa.armar(evento);
        rabbit.send("credpay.processamento.v1", "transacao.processada.v1", mensagem(evento, original.id(), causa, status));
        try {
            assertThat(pausa.inserida.await(10, TimeUnit.SECONDS)).as("escritas feitas antes do commit").isTrue();
            assertThat(estado(original.id())).isEqualTo("PENDENTE");
            assertThat(historico.buscarPorEvento(evento)).isEmpty();
            aguardarFila("messages_unacknowledged", 1, 15);
        } finally {
            pausa.liberar();
        }
        aguardarHistorico(evento);
        assertThat(estado(original.id())).isEqualTo(status.name());
        assertThat(historico.buscarPorEvento(evento)).contains(pausa.registrada);
        assertThat(pausa.registrada.occurredAt()).isEqualTo(OCORRIDO);
        assertThat(clock.chamadas.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM historico_transacoes WHERE transaction_id = ?",
                Integer.class, original.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_eventos WHERE aggregate_id = ?",
                Integer.class, original.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT scale(valor) FROM transacoes WHERE id = ?", Integer.class, original.id())).isEqualTo(3);
        aguardarFila("messages", 0, 10);
        assertThat(rabbit.receive(RabbitMqResultadoConfiguration.DLQ, 200)).isNull();
    }

    private String estado(UUID id) {
        return jdbc.queryForObject("SELECT status FROM transacoes WHERE id = ?", String.class, id);
    }

    @Test
    void receber_deveConfirmarReplaySemNovaTransicaoOuConsultaAoRelogio() throws Exception {
        var original = criar.executar(UUID.randomUUID(), new BigDecimal("123.450"), Currency.getInstance("BRL"));
        var causa = jdbc.queryForObject("SELECT event_id FROM outbox_eventos WHERE aggregate_id = ?", UUID.class, original.id());
        var evento = UUID.randomUUID();
        var message = mensagem(evento, original.id(), causa, StatusTransacao.REJEITADA);
        var outboxOriginal = jdbc.queryForMap("SELECT * FROM outbox_eventos WHERE event_id = ?", causa);
        clock.resetar();
        pausa.armarReplay(evento);
        rabbit.send("credpay.processamento.v1", "transacao.processada.v1", message);
        aguardarHistorico(evento);
        aguardarFila("messages", 0, 10);
        var transicaoOriginal = historico.buscarPorEvento(evento).orElseThrow();

        rabbit.send("credpay.processamento.v1", "transacao.processada.v1", message);
        try {
            assertThat(pausa.segundaBusca.await(10, TimeUnit.SECONDS)).as("segunda entrega equivalente observada").isTrue();
            aguardarFila("messages_unacknowledged", 1, 15);
            assertThat(historico.buscarPorEvento(evento)).contains(transicaoOriginal);
            assertThat(estado(original.id())).isEqualTo("REJEITADA");
            assertThat(clock.chamadas.get()).isEqualTo(1);
        } finally {
            pausa.liberar();
        }
        aguardarFila("messages", 0, 10);
        assertThat(pausa.buscas.get()).isEqualTo(2);
        assertThat(pausa.escritas.get()).isEqualTo(1);
        assertThat(clock.chamadas.get()).isEqualTo(1);
        assertThat(historico.buscarPorEvento(evento)).contains(transicaoOriginal);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM historico_transacoes WHERE transaction_id = ?",
                Integer.class, original.id())).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT * FROM outbox_eventos WHERE event_id = ?", causa))
                .withFailMessage("outbox original alterada pelo replay de resultado").isEqualTo(outboxOriginal);
        assertThat(rabbit.receive(RabbitMqResultadoConfiguration.DLQ, 200)).isNull();
    }

    @Test
    void receber_deveEnviarJsonInvalidoParaDlqSemAlterarBanco() throws Exception {
        var antes = banco();
        clock.resetar();
        var message = new Message("{".getBytes(StandardCharsets.UTF_8), new MessageProperties());

        rabbit.send("credpay.processamento.v1", "transacao.processada.v1", message);

        conferirRejeicao(message);
        assertThat(banco()).withFailMessage("contrato inválido alterou o banco").isEqualTo(antes);
        assertThat(clock.chamadas.get()).isZero();
    }

    @Test
    void receber_deveEnviarConflitoParaDlqPreservandoResultadoOriginal() throws Exception {
        var original = criar.executar(UUID.randomUUID(), new BigDecimal("123.450"), Currency.getInstance("BRL"));
        var causa = jdbc.queryForObject("SELECT event_id FROM outbox_eventos WHERE aggregate_id = ?", UUID.class, original.id());
        var evento = UUID.randomUUID();
        clock.resetar();
        rabbit.send("credpay.processamento.v1", "transacao.processada.v1",
                mensagem(evento, original.id(), causa, StatusTransacao.APROVADA));
        aguardarHistorico(evento);
        aguardarFila("messages", 0, 10);
        var antes = banco();
        var conflitante = mensagem(evento, original.id(), causa, StatusTransacao.REJEITADA);

        rabbit.send("credpay.processamento.v1", "transacao.processada.v1", conflitante);

        conferirRejeicao(conflitante);
        assertThat(banco()).withFailMessage("conflito alterou resultado, histórico ou outbox").isEqualTo(antes);
        assertThat(estado(original.id())).isEqualTo("APROVADA");
        assertThat(clock.chamadas.get()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void receber_deveEnviarRecusaParaDlqSemCriarTransicao(boolean transacaoExiste) throws Exception {
        var id = transacaoExiste
                ? criar.executar(UUID.randomUUID(), new BigDecimal("123.450"), Currency.getInstance("BRL")).id()
                : UUID.randomUUID();
        var antes = banco();
        clock.resetar();
        var message = mensagem(UUID.randomUUID(), id, UUID.randomUUID(), StatusTransacao.APROVADA);

        rabbit.send("credpay.processamento.v1", "transacao.processada.v1", message);

        conferirRejeicao(message);
        assertThat(banco()).withFailMessage("recusa causal alterou o banco").isEqualTo(antes);
        if (transacaoExiste) assertThat(estado(id)).isEqualTo("PENDENTE");
        else assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transacoes WHERE id = ?", Integer.class, id)).isZero();
        assertThat(clock.chamadas.get()).isZero();
    }

    private java.util.List<java.util.List<java.util.Map<String, Object>>> banco() {
        return java.util.List.of(
                jdbc.queryForList("SELECT * FROM transacoes ORDER BY id"),
                jdbc.queryForList("SELECT * FROM historico_transacoes ORDER BY event_id"),
                jdbc.queryForList("SELECT * FROM outbox_eventos ORDER BY event_id"));
    }

    private void conferirRejeicao(Message original) throws Exception {
        var rejeitada = rabbit.receive(RabbitMqResultadoConfiguration.DLQ, 10_000);
        assertThat(rejeitada).as("mensagem rejeitada recebida na DLQ real").isNotNull();
        assertThat(rejeitada.getBody()).withFailMessage("corpo da mensagem rejeitada foi alterado").isEqualTo(original.getBody());
        assertThat(rejeitada.getMessageProperties().getMessageId()).isEqualTo(original.getMessageProperties().getMessageId());
        assertThat(rejeitada.getMessageProperties().getCorrelationId()).isEqualTo(original.getMessageProperties().getCorrelationId());
        assertThat(rejeitada.getMessageProperties().getType()).isEqualTo(original.getMessageProperties().getType());
        assertThat(rejeitada.getMessageProperties().getHeaders()).containsEntry("x-first-death-reason", "rejected");
        assertThat(rejeitada.getMessageProperties().getHeaders()).containsEntry("x-first-death-queue", ENTRADA);
        aguardarFila("messages", 0, 10);
        assertThat(rabbit.receive(RabbitMqResultadoConfiguration.DLQ, 200)).isNull();
    }

    private void aguardarHistorico(UUID evento) throws InterruptedException {
        var prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (historico.buscarPorEvento(evento).isEmpty() && System.nanoTime() < prazo) TimeUnit.MILLISECONDS.sleep(50);
        assertThat(historico.buscarPorEvento(evento)).isPresent();
    }

    private void aguardarConsumidorExato() throws Exception {
        var prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            var resultado = RABBITMQ.execInContainer("rabbitmqctl", "--timeout", "5", "--quiet", "--formatter=json",
                    "list_consumers", "-p", "/");
            assertThat(resultado.getExitCode()).isZero();
            var consumidores = JSON.readTree(resultado.getStdout());
            var encontrados = StreamSupport.stream(consumidores.spliterator(), false)
                    .filter(linha -> ENTRADA.equals(linha.path("queue_name").asText())).toList();
            if (!encontrados.isEmpty()) {
                assertThat(encontrados).hasSize(1);
                assertThat(encontrados.getFirst().path("ack_required").asBoolean()).isTrue();
                assertThat(encontrados.getFirst().path("prefetch_count").asInt()).isEqualTo(1);
                return;
            }
            TimeUnit.MILLISECONDS.sleep(200);
        } while (System.nanoTime() < prazo);
        throw new AssertionError("consumidor exato de retorno não registrado no broker");
    }

    private JsonNode fila() throws Exception {
        return porNome(consultarFilas(), ENTRADA);
    }

    private JsonNode consultarFilas() throws Exception {
        var resultado = RABBITMQ.execInContainer("rabbitmqctl", "--timeout", "5", "--quiet", "--formatter=json",
                "list_queues", "-p", "/", "name", "policy", "effective_policy_definition", "messages", "messages_unacknowledged");
        assertThat(resultado.getExitCode()).as("consulta de estado do broker").isZero();
        return JSON.readTree(resultado.getStdout());
    }

    private JsonNode porNome(JsonNode filas, String nome) {
        var encontradas = StreamSupport.stream(filas.spliterator(), false)
                .filter(linha -> nome.equals(linha.path("name").asText())).toList();
        assertThat(encontradas).hasSize(1);
        return encontradas.getFirst();
    }

    private void aguardarFila(String campo, int esperado, int segundos) throws Exception {
        var prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(segundos);
        do {
            if (fila().path(campo).asInt() == esperado) return;
            TimeUnit.MILLISECONDS.sleep(200);
        } while (System.nanoTime() < prazo);
        throw new AssertionError("estado da fila não atingiu " + campo + "=" + esperado);
    }

    private Message mensagem(UUID evento, UUID id, UUID causa, StatusTransacao status) {
        var body = """
                {"eventId":"%s","eventType":"TransacaoProcessada","eventVersion":1,"occurredAt":"%s",
                 "correlationId":"%s","causationId":"%s","data":{"transactionId":"%s","status":"%s"}}
                """.formatted(evento, OCORRIDO, id, causa, id, status);
        var properties = new MessageProperties();
        properties.setMessageId(evento.toString());
        properties.setType("TransacaoProcessada");
        properties.setCorrelationId(id.toString());
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixture {
        @Bean DirectExchange producerExchangeFixture() { return new DirectExchange("credpay.processamento.v1", true, false); }
        @Bean @Primary HistoricoComPausa historicoComPausa(@Qualifier("transicaoJdbcRepository") TransicaoRepository delegate) {
            return new HistoricoComPausa(delegate);
        }
        @Bean @Primary RelogioContado relogioContado() { return new RelogioContado(Clock.systemUTC(), new AtomicInteger()); }
    }

    static class HistoricoComPausa implements TransicaoRepository {
        private final TransicaoRepository delegate;
        private volatile UUID alvo;
        private volatile CountDownLatch inserida = new CountDownLatch(0);
        private volatile CountDownLatch liberada = new CountDownLatch(0);
        private volatile TransicaoRecebida registrada;
        private volatile boolean replay;
        private volatile CountDownLatch segundaBusca = new CountDownLatch(0);
        private final AtomicInteger buscas = new AtomicInteger();
        private final AtomicInteger escritas = new AtomicInteger();

        HistoricoComPausa(TransicaoRepository delegate) { this.delegate = delegate; }
        void armar(UUID evento) {
            alvo = evento; replay = false; inserida = new CountDownLatch(1); liberada = new CountDownLatch(1);
            buscas.set(0); escritas.set(0);
        }
        void armarReplay(UUID evento) {
            armar(evento); replay = true; segundaBusca = new CountDownLatch(1);
        }
        void liberar() { liberada.countDown(); }
        @Override public void bloquearIdentidades(UUID evento, UUID transacao) { delegate.bloquearIdentidades(evento, transacao); }
        @Override public Optional<TransicaoRecebida> buscarPorEvento(UUID evento) {
            var transicao = delegate.buscarPorEvento(evento);
            if (evento.equals(alvo) && buscas.incrementAndGet() == 2 && replay) {
                segundaBusca.countDown();
                aguardarLiberacao();
            }
            return transicao;
        }
        @Override public boolean existeCriacao(UUID causa, UUID transacao) { return delegate.existeCriacao(causa, transacao); }
        @Override public void registrar(TransicaoRecebida transicao) {
            delegate.registrar(transicao);
            if (transicao.eventId().equals(alvo)) {
                escritas.incrementAndGet();
                registrada = transicao;
                if (!replay) {
                    inserida.countDown();
                    aguardarLiberacao();
                }
            }
        }
        private void aguardarLiberacao() {
            try {
                if (!liberada.await(45, TimeUnit.SECONDS)) throw new IllegalStateException("pausa transacional excedida");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("pausa transacional interrompida");
            }
        }
    }

    static class RelogioContado extends Clock {
        private final Clock delegate;
        private final AtomicInteger chamadas;
        RelogioContado(Clock delegate, AtomicInteger chamadas) { this.delegate = delegate; this.chamadas = chamadas; }
        void resetar() { chamadas.set(0); }
        @Override public ZoneId getZone() { return delegate.getZone(); }
        @Override public Clock withZone(ZoneId zone) { return new RelogioContado(delegate.withZone(zone), chamadas); }
        @Override public Instant instant() { chamadas.incrementAndGet(); return delegate.instant(); }
    }
}
