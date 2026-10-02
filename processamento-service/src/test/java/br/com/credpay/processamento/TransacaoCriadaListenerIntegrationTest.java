package br.com.credpay.processamento;

import static org.assertj.core.api.Assertions.assertThat;

import br.com.credpay.processamento.application.EventoSaidaPendente;
import br.com.credpay.processamento.application.OutboxProcessamentoRepository;
import br.com.credpay.processamento.application.ProcessamentoRegistrado;
import br.com.credpay.processamento.application.ProcessamentoRepository;
import br.com.credpay.processamento.support.ProducerExchangeFixture;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Optional;
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
    @Autowired private ObservadorDeBusca observador;

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

    @Test
    void deveConfirmarReplayEquivalenteSemDuplicarResultadoOuOutbox() throws Exception {
        var eventId = UUID.randomUUID();
        var transactionId = UUID.randomUUID();
        var mensagem = mensagem(eventId, transactionId);
        pausa.desarmar();
        observador.armar(eventId);

        rabbitTemplate.send("credpay.transacoes.v1", "transacao.criada.v1", mensagem);
        var prazo = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (quantidadeNoBanco("outbox_eventos", "aggregate_id", transactionId) == 0
                && System.nanoTime() < prazo) {
            TimeUnit.MILLISECONDS.sleep(50);
        }
        assertThat(quantidadeNoBanco("processamentos", "event_id", eventId)).isEqualTo(1);
        var outputEventId = jdbc.queryForObject(
                "select output_event_id from processamentos where event_id = ?", UUID.class, eventId);

        rabbitTemplate.send("credpay.transacoes.v1", "transacao.criada.v1", mensagem);
        try {
            assertThat(observador.aguardarSegundaBusca(10, TimeUnit.SECONDS)).isTrue();
            assertThat(quantidadeNoBanco("processamentos", "event_id", eventId)).isEqualTo(1);
            assertThat(quantidadeNoBanco("outbox_eventos", "aggregate_id", transactionId)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "select output_event_id from processamentos where event_id = ?", UUID.class, eventId))
                    .isEqualTo(outputEventId);
            aguardarEstadoFila("messages_unacknowledged", 1, 20);
        } finally {
            observador.liberar();
        }
        aguardarEstadoFila("messages", 0, 10);
    }

    @Test
    void deveRejeitarJsonInvalidoDiretamenteParaDlqSemDecisao() throws Exception {
        var politica = "{\"dead-letter-strategy\":\"at-least-once\","
                + "\"overflow\":\"reject-publish\",\"max-length\":10000,"
                + "\"dead-letter-exchange\":\"credpay.processamento.dlx.v1\","
                + "\"dead-letter-routing-key\":\"transacao.criada.dlq.v1\"}";
        var configuracao = RABBITMQ.execInContainer("rabbitmqctl", "set_policy", "--apply-to",
                "quorum_queues", "credpay-processing-input",
                "^credpay[.]processamento[.]transacao-criada[.]v1$", politica);
        assertThat(configuracao.getExitCode()).isZero();
        var efetiva = RABBITMQ.execInContainer("rabbitmqctl", "list_queues", "name",
                "effective_policy_definition");
        assertThat(efetiva.getStdout()).contains("at-least-once", "reject-publish");
        var resultadosAntes = totalNoBanco("processamentos");
        var intencoesAntes = totalNoBanco("outbox_eventos");

        rabbitTemplate.send("credpay.transacoes.v1", "transacao.criada.v1",
                new Message("{".getBytes(StandardCharsets.UTF_8), new MessageProperties()));

        var morta = rabbitTemplate.receive("credpay.processamento.transacao-criada.dlq.v1", 10_000);
        assertThat(morta).isNotNull();
        assertThat(morta.getMessageProperties().getHeaders())
                .containsEntry("x-first-death-reason", "rejected");
        assertThat(totalNoBanco("processamentos")).isEqualTo(resultadosAntes);
        assertThat(totalNoBanco("outbox_eventos")).isEqualTo(intencoesAntes);
    }

    private int quantidadeNoBanco(String tabela, String coluna, UUID id) {
        return jdbc.queryForObject("select count(*) from " + tabela + " where " + coluna + " = ?",
                Integer.class, id);
    }

    private int totalNoBanco(String tabela) {
        return jdbc.queryForObject("select count(*) from " + tabela, Integer.class);
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

        @Bean
        @Primary
        ObservadorDeBusca processamentoComObservador(
                @Qualifier("processamentoJpaRepository") ProcessamentoRepository delegate) {
            return new ObservadorDeBusca(delegate);
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

        void desarmar() {
            inserida = null;
            liberada = null;
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
            var sinal = inserida;
            if (sinal == null) {
                return;
            }
            sinal.countDown();
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

    static class ObservadorDeBusca implements ProcessamentoRepository {
        private final ProcessamentoRepository delegate;
        private final AtomicInteger buscas = new AtomicInteger();
        private volatile UUID eventIdObservado;
        private volatile CountDownLatch segundaBusca;
        private volatile CountDownLatch liberada;

        ObservadorDeBusca(ProcessamentoRepository delegate) {
            this.delegate = delegate;
        }

        void armar(UUID eventId) {
            eventIdObservado = eventId;
            buscas.set(0);
            segundaBusca = new CountDownLatch(1);
            liberada = new CountDownLatch(1);
        }

        boolean aguardarSegundaBusca(long tempo, TimeUnit unidade) throws InterruptedException {
            return segundaBusca.await(tempo, unidade);
        }

        void liberar() {
            liberada.countDown();
        }

        @Override
        public void bloquearIdentidades(UUID eventId, UUID transactionId) {
            delegate.bloquearIdentidades(eventId, transactionId);
        }

        @Override
        public void inserir(ProcessamentoRegistrado processamento) {
            delegate.inserir(processamento);
        }

        @Override
        public Optional<ProcessamentoRegistrado> buscarPorEventId(UUID eventId) {
            var encontrado = delegate.buscarPorEventId(eventId);
            if (eventId.equals(eventIdObservado) && buscas.incrementAndGet() == 2) {
                segundaBusca.countDown();
                try {
                    if (!liberada.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("espera de replay excedida");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("espera de replay interrompida", exception);
                }
            }
            return encontrado;
        }

        @Override
        public boolean existePorTransactionId(UUID transactionId) {
            return delegate.existePorTransactionId(transactionId);
        }
    }
}
