package br.com.credpay.transacoes.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import br.com.credpay.transacoes.domain.StatusTransacao;
import tools.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Import({SolicitarEncerramentoService.class, SolicitarEncerramentoIntegrationTest.Configuracao.class})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class SolicitarEncerramentoIntegrationTest {

    private static HikariDataSource poolDaFixture;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgresDaFixture()
            .withDatabaseName("credpay_solicitar_encerramento").withUsername("test").withPassword("test");

    private static final class PostgresDaFixture extends PostgreSQLContainer {
        private PostgresDaFixture() {
            super("postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0");
        }

        @Override public void stop() {
            try {
                assertThat(poolDaFixture).isNotNull();
                assertThat(isRunning()).isTrue();
                assertThat(poolDaFixture.isClosed()).isTrue();
            } finally {
                super.stop();
            }
        }
    }

    @DynamicPropertySource
    static void configurarBanco(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private CriarTransacao criar;
    @Autowired private SolicitarEncerramentoService solicitar;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private HikariDataSource dataSource;
    @Autowired private ObjectMapper mapper;
    @Autowired private AplicarResultadoService aplicar;
    @Autowired private RelogioControlado clock;
    @Autowired private ControleEscrita controle;

    @BeforeEach void preparar() {
        poolDaFixture = dataSource;
        clock.instante = Instant.parse("2026-10-14T18:00:00.123456789Z");
        clock.chamadas.set(0);
        controle.desarmar();
        controle.falharPedido.set(false);
        jdbc.execute("""
                TRUNCATE respostas_encerramento_recebidas, outbox_pedidos_encerramento, pedidos_encerramento,
                  historico_transacoes, outbox_eventos, idempotencias_transacao, transacoes
                """);
    }

    @Test
    void executar_deveGravarPedidoEIntencao_semMudarTransacaoOuRespostaOriginal() throws Exception {
        var chave = UUID.randomUUID();
        var original = criar.executar(chave, new BigDecimal("75.00"), Currency.getInstance("BRL"));
        var transacao = jdbc.queryForMap("SELECT * FROM transacoes");
        var criacao = jdbc.queryForMap("SELECT * FROM outbox_eventos");
        var historico = jdbc.queryForList("SELECT * FROM historico_transacoes");
        var pedido = UUID.randomUUID();

        var evidencia = UUID.randomUUID();
        var registrado = solicitar.executar(pedido, original.id(), evidencia);

        var quantidade = jdbc.queryForObject("SELECT count(*) FROM pedidos_encerramento WHERE event_id = ?",
                Integer.class, pedido);
        assertThat(quantidade).withFailMessage("pedido esperado1, observado%s", quantidade).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pedidos_encerramento p JOIN outbox_pedidos_encerramento o
                  ON o.event_id = p.event_id AND o.aggregate_id = p.transaction_id
                WHERE p.event_id = ? AND o.published_at IS NULL
                """, Integer.class, pedido)).isEqualTo(1);
        var snapshot = jdbc.queryForMap("SELECT * FROM pedidos_encerramento WHERE event_id = ?", pedido);
        assertThat(snapshot).containsEntry("transaction_id", original.id())
                .containsEntry("creation_event_id", criacao.get("event_id"))
                .containsEntry("correlation_id", original.id())
                .containsEntry("motivo", "FALHA_PERMANENTE_CONTROLADA")
                .containsEntry("evidence_ref", evidencia);
        assertThat(Instant.ofEpochSecond(((Number) snapshot.get("occurred_epoch_second")).longValue(),
                ((Number) snapshot.get("occurred_nano")).longValue())).isEqualTo(registrado.occurredAt());
        assertThat(jdbc.queryForMap("SELECT * FROM outbox_pedidos_encerramento WHERE event_id = ?", pedido))
                .containsEntry("occurred_epoch_second", snapshot.get("occurred_epoch_second"))
                .containsEntry("occurred_nano", 123456789)
                .containsEntry("event_type", "EncerramentoTransacaoSolicitado")
                .containsEntry("event_version", 1);
        var json = mapper.readTree(jdbc.queryForObject(
                "SELECT payload::text FROM outbox_pedidos_encerramento WHERE event_id = ?", String.class, pedido));
        assertThat(json.path("eventId").asText()).isEqualTo(pedido.toString());
        assertThat(json.path("eventType").asText()).isEqualTo("EncerramentoTransacaoSolicitado");
        assertThat(json.path("eventVersion").asInt()).isEqualTo(1);
        assertThat(json.path("occurredAt").asText()).isEqualTo(registrado.occurredAt().toString());
        assertThat(json.path("correlationId").asText()).isEqualTo(original.id().toString());
        assertThat(json.path("causationId").asText()).isEqualTo(criacao.get("event_id").toString());
        assertThat(json.path("data").size()).isEqualTo(4);
        assertThat(json.path("data").path("transactionId").asText()).isEqualTo(original.id().toString());
        assertThat(json.path("data").path("creationEventId").asText()).isEqualTo(criacao.get("event_id").toString());
        assertThat(json.path("data").path("motivo").asText()).isEqualTo("FALHA_PERMANENTE_CONTROLADA");
        assertThat(json.path("data").path("evidenceRef").asText()).isEqualTo(evidencia.toString());
        assertThat(jdbc.queryForMap("SELECT * FROM transacoes")).isEqualTo(transacao);
        assertThat(jdbc.queryForMap("SELECT * FROM outbox_eventos")).isEqualTo(criacao);
        assertThat(jdbc.queryForList("SELECT * FROM historico_transacoes")).isEqualTo(historico);
        assertThat(criar.executar(chave, new BigDecimal("75.0"), Currency.getInstance("BRL"))).isEqualTo(original);
    }

    @Test
    void executar_deveRecusarTransacaoInexistente_semEscrita() {
        assertThatThrownBy(() -> solicitar.executar(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(PedidoEncerramentoRecusadoException.class)
                .hasMessage("transação inexistente ou não pendente");
        assertSemPedido();
    }

    @ParameterizedTest
    @EnumSource(value = StatusTransacao.class, names = {"APROVADA", "REJEITADA"})
    void executar_deveRecusarTransacaoFinal_semAlterarVencedor(StatusTransacao estado) {
        var original = criar.executar(UUID.randomUUID(), new BigDecimal("75.00"), Currency.getInstance("BRL"));
        var criacao = jdbc.queryForObject("SELECT event_id FROM outbox_eventos", UUID.class);
        aplicar.executar(new TransacaoProcessadaRecebida(UUID.randomUUID(), original.id(),
                Instant.parse("2026-10-14T18:00:00.123456789Z"), original.id(), criacao, estado));
        var transacao = jdbc.queryForMap("SELECT * FROM transacoes");
        var historico = jdbc.queryForList("SELECT * FROM historico_transacoes");

        assertThatThrownBy(() -> solicitar.executar(UUID.randomUUID(), original.id(), UUID.randomUUID()))
                .isInstanceOf(PedidoEncerramentoRecusadoException.class)
                .hasMessage("transação inexistente ou não pendente");

        assertSemPedido();
        assertThat(jdbc.queryForMap("SELECT * FROM transacoes")).isEqualTo(transacao);
        assertThat(jdbc.queryForList("SELECT * FROM historico_transacoes")).isEqualTo(historico);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ausente", "tipo", "versao", "ambigua"})
    void executar_deveRecusarCriacaoNaoUnivoca_semEscrita(String cenario) {
        var original = criar.executar(UUID.randomUUID(), new BigDecimal("75.00"), Currency.getInstance("BRL"));
        switch (cenario) {
            case "ausente" -> jdbc.update("DELETE FROM outbox_eventos");
            case "tipo" -> jdbc.update("UPDATE outbox_eventos SET event_type = 'OutroEvento'");
            case "versao" -> jdbc.update("UPDATE outbox_eventos SET event_version = 2");
            case "ambigua" -> jdbc.update("""
                    INSERT INTO outbox_eventos (event_id, aggregate_id, event_type, event_version, payload, occurred_at)
                    SELECT ?, aggregate_id, event_type, event_version, payload, occurred_at FROM outbox_eventos
                    """, UUID.randomUUID());
            default -> throw new IllegalArgumentException(cenario);
        }
        var criacao = jdbc.queryForList("SELECT * FROM outbox_eventos ORDER BY event_id");

        assertThatThrownBy(() -> solicitar.executar(UUID.randomUUID(), original.id(), UUID.randomUUID()))
                .isInstanceOf(PedidoEncerramentoRecusadoException.class)
                .hasMessage("criação original ausente ou ambígua");

        assertSemPedido();
        assertThat(jdbc.queryForList("SELECT * FROM outbox_eventos ORDER BY event_id")).isEqualTo(criacao);
        assertThat(jdbc.queryForObject("SELECT status FROM transacoes", String.class)).isEqualTo("PENDENTE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM historico_transacoes", Integer.class)).isZero();
    }

    private void assertSemPedido() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pedidos_encerramento", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_pedidos_encerramento", Integer.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDENTE", "APROVADA", "REJEITADA"})
    void executar_deveRepetirPedidoOriginal_antesDoEstadoOuRelogio(String estado) {
        var original = criar.executar(UUID.randomUUID(), new BigDecimal("75.00"), Currency.getInstance("BRL"));
        var pedidoId = UUID.randomUUID();
        var evidencia = UUID.randomUUID();
        var pedido = solicitar.executar(pedidoId, original.id(), evidencia);
        jdbc.update("UPDATE outbox_pedidos_encerramento SET published_at = CURRENT_TIMESTAMP");
        if (!estado.equals("PENDENTE")) {
            aplicar.executar(new TransacaoProcessadaRecebida(UUID.randomUUID(), original.id(), clock.instante,
                    original.id(), pedido.creationEventId(), StatusTransacao.valueOf(estado)));
        }
        var antes = jdbc.queryForMap("SELECT * FROM pedidos_encerramento");
        var intencao = jdbc.queryForMap("SELECT * FROM outbox_pedidos_encerramento");
        var transacao = jdbc.queryForMap("SELECT * FROM transacoes");
        var historico = jdbc.queryForList("SELECT * FROM historico_transacoes");
        var chamadas = clock.chamadas.get();
        clock.instante = clock.instante.plusSeconds(100);

        assertThat(solicitar.executar(pedidoId, original.id(), evidencia)).isEqualTo(pedido);

        assertThat(clock.chamadas.get()).isEqualTo(chamadas);
        assertThat(jdbc.queryForMap("SELECT * FROM pedidos_encerramento")).isEqualTo(antes);
        assertThat(jdbc.queryForMap("SELECT * FROM outbox_pedidos_encerramento")).isEqualTo(intencao);
        assertThat(jdbc.queryForMap("SELECT * FROM transacoes")).isEqualTo(transacao);
        assertThat(jdbc.queryForList("SELECT * FROM historico_transacoes")).isEqualTo(historico);
    }

    @ParameterizedTest
    @ValueSource(strings = {"evidencia", "transacao", "pedido"})
    void executar_deveRecusarPedidoDivergente_semSubstituirOriginal(String campo) {
        var original = criar.executar(UUID.randomUUID(), new BigDecimal("75.00"), Currency.getInstance("BRL"));
        var outra = criar.executar(UUID.randomUUID(), new BigDecimal("20.00"), Currency.getInstance("BRL"));
        var pedidoId = UUID.randomUUID();
        var evidencia = UUID.randomUUID();
        solicitar.executar(pedidoId, original.id(), evidencia);
        var antes = jdbc.queryForMap("SELECT * FROM pedidos_encerramento");
        var intencao = jdbc.queryForMap("SELECT * FROM outbox_pedidos_encerramento");
        var chamadas = clock.chamadas.get();

        assertThatThrownBy(() -> solicitar.executar(campo.equals("pedido") ? UUID.randomUUID() : pedidoId,
                campo.equals("transacao") ? outra.id() : original.id(),
                campo.equals("evidencia") ? UUID.randomUUID() : evidencia))
                .isInstanceOf(PedidoEncerramentoRecusadoException.class)
                .hasMessage("pedido de encerramento divergente");

        assertThat(clock.chamadas.get()).isEqualTo(chamadas);
        assertThat(jdbc.queryForMap("SELECT * FROM pedidos_encerramento")).isEqualTo(antes);
        assertThat(jdbc.queryForMap("SELECT * FROM outbox_pedidos_encerramento")).isEqualTo(intencao);
    }

    @ParameterizedTest
    @ValueSource(strings = {"origem_inexistente", "origem_outra_transacao", "correlacao", "motivo", "nano_pedido",
            "intencao_orfa", "agregado_intencao", "tipo_intencao", "versao_intencao", "payload_intencao",
            "nano_intencao"})
    void banco_deveRecusarQuebraDeCausalidadeOuEstrutura_semAlterarPar(String cenario) {
        var original = criar.executar(UUID.randomUUID(), new BigDecimal("75.00"), Currency.getInstance("BRL"));
        var outra = criar.executar(UUID.randomUUID(), new BigDecimal("20.00"), Currency.getInstance("BRL"));
        solicitar.executar(UUID.randomUUID(), original.id(), UUID.randomUUID());
        var antes = jdbc.queryForMap("SELECT * FROM pedidos_encerramento");
        var intencao = jdbc.queryForMap("SELECT * FROM outbox_pedidos_encerramento");
        var outraCriacao = jdbc.queryForObject("SELECT event_id FROM outbox_eventos WHERE aggregate_id = ?",
                UUID.class, outra.id());

        assertThatThrownBy(() -> {
            switch (cenario) {
                case "origem_inexistente" -> jdbc.update(
                        "UPDATE pedidos_encerramento SET creation_event_id = ?", UUID.randomUUID());
                case "origem_outra_transacao" -> jdbc.update(
                        "UPDATE pedidos_encerramento SET creation_event_id = ?", outraCriacao);
                case "correlacao" -> jdbc.update(
                        "UPDATE pedidos_encerramento SET correlation_id = ?", outra.id());
                case "motivo" -> jdbc.update("UPDATE pedidos_encerramento SET motivo = 'OUTRO'");
                case "nano_pedido" -> jdbc.update("UPDATE pedidos_encerramento SET occurred_nano = -1");
                case "intencao_orfa" -> jdbc.update(
                        "UPDATE outbox_pedidos_encerramento SET event_id = ?", UUID.randomUUID());
                case "agregado_intencao" -> jdbc.update(
                        "UPDATE outbox_pedidos_encerramento SET aggregate_id = ?", outra.id());
                case "tipo_intencao" -> jdbc.update("UPDATE outbox_pedidos_encerramento SET event_type = 'OUTRO'");
                case "versao_intencao" -> jdbc.update("UPDATE outbox_pedidos_encerramento SET event_version = 2");
                case "payload_intencao" -> jdbc.update("UPDATE outbox_pedidos_encerramento SET payload = '[]'::jsonb");
                case "nano_intencao" -> jdbc.update("UPDATE outbox_pedidos_encerramento SET occurred_nano = 1000000000");
                default -> throw new IllegalArgumentException(cenario);
            }
        }).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForMap("SELECT * FROM pedidos_encerramento")).isEqualTo(antes);
        assertThat(jdbc.queryForMap("SELECT * FROM outbox_pedidos_encerramento")).isEqualTo(intencao);
    }

    @Test
    void banco_deveImpedirSegundoPedidoDaMesmaTransacao() {
        var original = criar.executar(UUID.randomUUID(), new BigDecimal("75.00"), Currency.getInstance("BRL"));
        solicitar.executar(UUID.randomUUID(), original.id(), UUID.randomUUID());

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO pedidos_encerramento
                  (event_id, transaction_id, creation_event_id, occurred_epoch_second, occurred_nano,
                   correlation_id, motivo, evidence_ref)
                SELECT ?, transaction_id, creation_event_id, occurred_epoch_second, occurred_nano,
                       correlation_id, motivo, evidence_ref FROM pedidos_encerramento
                """, UUID.randomUUID())).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM pedidos_encerramento", Integer.class)).isEqualTo(1);
    }

    @Test
    void executar_deveReverterPedidoEIntencao_quandoSegundaEscritaFalhar_ePermitirRetry() {
        var original = criar.executar(UUID.randomUUID(), new BigDecimal("75.00"), Currency.getInstance("BRL"));
        var transacao = jdbc.queryForMap("SELECT * FROM transacoes");
        var criacao = jdbc.queryForMap("SELECT * FROM outbox_eventos");
        var pedido = UUID.randomUUID();
        var evidencia = UUID.randomUUID();
        controle.falharPedido.set(true);

        assertThatThrownBy(() -> solicitar.executar(pedido, original.id(), evidencia))
                .isInstanceOf(IllegalStateException.class).hasMessage("falha após intenção real");

        assertSemPedido();
        assertThat(jdbc.queryForMap("SELECT * FROM transacoes")).isEqualTo(transacao);
        assertThat(jdbc.queryForMap("SELECT * FROM outbox_eventos")).isEqualTo(criacao);
        assertThat(jdbc.queryForList("SELECT * FROM historico_transacoes")).isEmpty();
        controle.falharPedido.set(false);
        assertThat(solicitar.executar(pedido, original.id(), evidencia).eventId()).isEqualTo(pedido);
        assertParUnico();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void executar_deveSerializarPedidosConcorrentes(boolean equivalente) throws Exception {
        var original = criar.executar(UUID.randomUUID(), new BigDecimal("75.00"), Currency.getInstance("BRL"));
        var pedido = UUID.randomUUID();
        var evidencia = UUID.randomUUID();
        var disputa = disputar(Etapa.PEDIDO, false, () -> solicitar.executar(pedido, original.id(), evidencia),
                () -> solicitar.executar(equivalente ? pedido : UUID.randomUUID(), original.id(), evidencia));

        assertThat(disputa.primeira().erro()).isNull();
        if (equivalente) {
            assertThat(disputa.segunda().erro()).isNull();
            assertThat(disputa.segunda().resultado()).isEqualTo(disputa.primeira().resultado());
        } else {
            assertThat(disputa.segunda().erro()).isInstanceOf(PedidoEncerramentoRecusadoException.class)
                    .hasMessage("pedido de encerramento divergente");
        }
        assertParUnico();
        assertThat(jdbc.queryForObject("SELECT status FROM transacoes", String.class)).isEqualTo("PENDENTE");
        assertThat(jdbc.queryForList("SELECT * FROM historico_transacoes")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"financeiro_commit", "pedido_commit", "financeiro_rollback", "pedido_rollback"})
    void executar_devePreservarOrdemRealDeCommit_semCongelarTransacao(String cenario) throws Exception {
        var original = criar.executar(UUID.randomUUID(), new BigDecimal("75.00"), Currency.getInstance("BRL"));
        var criacao = jdbc.queryForObject("SELECT event_id FROM outbox_eventos", UUID.class);
        var entrada = new TransacaoProcessadaRecebida(UUID.randomUUID(), original.id(), clock.instante,
                original.id(), criacao, StatusTransacao.APROVADA);
        var pedidoId = UUID.randomUUID();
        var evidencia = UUID.randomUUID();
        Callable<?> pedido = () -> solicitar.executar(pedidoId, original.id(), evidencia);
        Callable<?> financeiro = () -> aplicar.executar(entrada);
        boolean financeiroPrimeiro = cenario.startsWith("financeiro");
        boolean reverter = cenario.endsWith("rollback");

        var disputa = disputar(financeiroPrimeiro ? Etapa.FINANCEIRO : Etapa.PEDIDO, reverter,
                financeiroPrimeiro ? financeiro : pedido, financeiroPrimeiro ? pedido : financeiro);

        if (reverter) {
            assertThat(disputa.primeira().erro()).isInstanceOf(IllegalStateException.class)
                    .hasMessage("rollback concorrente controlado");
        } else { assertThat(disputa.primeira().erro()).isNull(); }
        if (financeiroPrimeiro && !reverter) {
            assertThat(disputa.segunda().erro()).isInstanceOf(PedidoEncerramentoRecusadoException.class)
                    .hasMessage("transação inexistente ou não pendente");
            assertSemPedido();
        } else {
            assertThat(disputa.segunda().erro()).isNull();
            if (financeiroPrimeiro || !reverter) { assertParUnico(); }
            else { assertSemPedido(); }
        }
        boolean financeiroConfirmado = !financeiroPrimeiro || !reverter;
        assertThat(jdbc.queryForObject("SELECT status FROM transacoes", String.class))
                .isEqualTo(financeiroConfirmado ? "APROVADA" : "PENDENTE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM historico_transacoes", Integer.class))
                .isEqualTo(financeiroConfirmado ? 1 : 0);
    }

    private void assertParUnico() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pedidos_encerramento", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_pedidos_encerramento", Integer.class)).isEqualTo(1);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuracao {
        @Bean @Primary RelogioControlado relogioControlado() { return new RelogioControlado(); }
        @Bean ControleEscrita controleEscrita() { return new ControleEscrita(); }

        @Bean @Primary OutboxPedidoEncerramentoRepository outboxControlada(
                @Qualifier("pedidoEncerramentoJdbcRepository") OutboxPedidoEncerramentoRepository real,
                ControleEscrita controle) {
            return evento -> {
                real.adicionar(evento);
                controle.segurar(Etapa.PEDIDO);
                if (controle.falharPedido.get()) { throw new IllegalStateException("falha após intenção real"); }
            };
        }

        @Bean @Primary TransicaoRepository historicoControlado(
                @Qualifier("transicaoJdbcRepository") TransicaoRepository real, ControleEscrita controle) {
            return new TransicaoRepository() {
                @Override public void bloquearIdentidades(UUID evento, UUID transacao) {
                    real.bloquearIdentidades(evento, transacao);
                }
                @Override public void registrar(TransicaoRecebida transicao) {
                    real.registrar(transicao);
                    controle.segurar(Etapa.FINANCEIRO);
                }
                @Override public Optional<TransicaoRecebida> buscarPorEvento(UUID evento) {
                    return real.buscarPorEvento(evento);
                }
                @Override public boolean existeCriacao(UUID causa, UUID transacao) {
                    return real.existeCriacao(causa, transacao);
                }
            };
        }
    }

    private enum Etapa { PEDIDO, FINANCEIRO }
    private record Porta(Etapa etapa, boolean falhar, CountDownLatch entrou, CountDownLatch liberar) { }
    private record Chamada(Object resultado, Throwable erro) { }
    private record Disputa(Chamada primeira, Chamada segunda) { }

    static class ControleEscrita {
        private final AtomicReference<Porta> atual = new AtomicReference<>();
        private final AtomicBoolean falharPedido = new AtomicBoolean();

        Porta armar(Etapa etapa, boolean falhar) {
            var porta = new Porta(etapa, falhar, new CountDownLatch(1), new CountDownLatch(1));
            atual.set(porta);
            return porta;
        }

        void desarmar() {
            var porta = atual.getAndSet(null);
            if (porta != null) { porta.liberar().countDown(); }
        }

        void segurar(Etapa etapa) {
            var porta = atual.get();
            if (porta == null || porta.etapa() != etapa) { return; }
            porta.entrou().countDown();
            try {
                if (!porta.liberar().await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("fixture não liberou escrita");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("fixture interrompida", exception);
            }
            if (porta.falhar()) { throw new IllegalStateException("rollback concorrente controlado"); }
        }
    }

    private Disputa disputar(Etapa etapa, boolean falhar, Callable<?> primeiraChamada,
            Callable<?> segundaChamada) throws Exception {
        var porta = controle.armar(etapa, falhar);
        var executor = Executors.newFixedThreadPool(2);
        Future<?> primeira = null;
        Future<?> segunda = null;
        try {
            primeira = executor.submit(primeiraChamada);
            assertThat(porta.entrou().await(10, TimeUnit.SECONDS)).isTrue();
            segunda = executor.submit(segundaChamada);
            aguardarLock();
            porta.liberar().countDown();
            return new Disputa(concluir(primeira), concluir(segunda));
        } finally {
            controle.desarmar();
            if (primeira != null && !primeira.isDone()) { primeira.cancel(true); }
            if (segunda != null && !segunda.isDone()) { segunda.cancel(true); }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Chamada concluir(Future<?> futuro) throws Exception {
        try {
            return new Chamada(futuro.get(10, TimeUnit.SECONDS), null);
        } catch (ExecutionException exception) {
            return new Chamada(null, exception.getCause());
        }
    }

    private void aguardarLock() {
        var fim = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < fim) {
            var aguardando = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted
                      AND database = (SELECT oid FROM pg_database WHERE datname = current_database())
                    """, Integer.class);
            if (aguardando != null && aguardando > 0) { return; }
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
        throw new AssertionError("segunda chamada não aguardou advisory lock");
    }

    static class RelogioControlado extends Clock {
        private volatile Instant instante = Instant.parse("2026-10-14T18:00:00.123456789Z");
        private final AtomicInteger chamadas = new AtomicInteger();

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { chamadas.incrementAndGet(); return instante; }
    }
}
