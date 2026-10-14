package br.com.credpay.processamento.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.zaxxer.hikari.HikariDataSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "credpay.processamento.limites.BRL=100.00")
@Import({RegistrarEncerramentoService.class, RegistrarEncerramentoIntegrationTest.RelogioControlado.class})
@Testcontainers
@Execution(ExecutionMode.SAME_THREAD)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RegistrarEncerramentoIntegrationTest {

    private static HikariDataSource poolDaFixture;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgresDaFixture()
            .withDatabaseName("credpay_registrar_encerramento")
            .withUsername("test").withPassword("test");

    private static final class PostgresDaFixture extends PostgreSQLContainer<PostgresDaFixture> {
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

    @Autowired private RegistrarEncerramentoService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private HikariDataSource dataSource;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private RegistrarProcessamentoService financeiro;
    @Autowired private RelogioContado relogio;
    @Autowired private GeradorContado gerador;
    @Autowired private OutboxEncerramentoControlada outboxControlada;
    @Autowired private DisputaControlada controle;

    @BeforeEach void limparBancoDescartavel() {
        poolDaFixture = dataSource;
        relogio.chamadas.set(0);
        relogio.instante.set(Instant.parse("2026-10-14T12:00:01.987654321Z"));
        gerador.chamadas.set(0);
        outboxControlada.falhar.set(false);
        controle.desarmar();
        jdbc.execute("TRUNCATE criacao_bloqueada_recebimentos, outbox_encerramentos, encerramentos, outbox_eventos, processamentos");
    }

    @AfterEach void liberarBarreira() { controle.desarmar(); }

    @Test
    void executar_deveCommitarRegistroERespostaPropria_quandoNaoExistirDecisao() throws Exception {
        var transacao = UUID.randomUUID();
        var pedido = new PedidoEncerramentoRecebido(UUID.randomUUID(), transacao, UUID.randomUUID(),
                Instant.parse("2026-10-14T12:00:00.123456789Z"), transacao,
                PedidoEncerramentoRecebido.Motivo.FALHA_PERMANENTE_CONTROLADA, UUID.randomUUID());

        var resultado = service.executar(pedido);

        var quantidade = jdbc.queryForObject("SELECT count(*) FROM encerramentos WHERE request_event_id = ?",
                Integer.class, pedido.eventId());
        assertThat(quantidade).withFailMessage("pedido apos commit: esperado1, observado%s", quantidade).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM encerramentos e JOIN outbox_encerramentos o
                  ON o.event_id = e.response_event_id AND o.aggregate_id = e.transaction_id
                WHERE e.request_event_id = ? AND e.resultado = 'CONFIRMADO'
                  AND o.published_at IS NULL
                """, Integer.class, pedido.eventId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processamentos", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_eventos", Integer.class)).isZero();
        assertThat(resultado.pedido()).isEqualTo(pedido);
        assertThat(resultado.resultado()).isEqualTo(EncerramentoRegistrado.Resultado.CONFIRMADO);
        assertThat(resultado.respondedAt()).isEqualTo(Instant.parse("2026-10-14T12:00:01.987654Z"));
        assertThat(resultado.outputEventId()).isEmpty();
        var snapshot = jdbc.queryForMap("SELECT * FROM encerramentos WHERE request_event_id = ?", pedido.eventId());
        assertThat(snapshot.get("transaction_id")).isEqualTo(pedido.transactionId());
        assertThat(snapshot.get("creation_event_id")).isEqualTo(pedido.creationEventId());
        assertThat(snapshot.get("correlation_id")).isEqualTo(pedido.correlationId());
        assertThat(snapshot.get("request_occurred_epoch_second")).isEqualTo(pedido.occurredAt().getEpochSecond());
        assertThat(snapshot.get("request_occurred_nano")).isEqualTo(pedido.occurredAt().getNano());
        assertThat(snapshot.get("evidence_id")).isEqualTo(pedido.evidenceRef());
        assertThat(snapshot.get("response_event_id")).isEqualTo(resultado.responseEventId());
        Instant respondidoNoBanco = jdbc.queryForObject(
                "SELECT responded_at FROM encerramentos WHERE request_event_id = ?",
                (rs, linha) -> rs.getTimestamp(1).toInstant(), pedido.eventId());
        assertThat(respondidoNoBanco).isEqualTo(resultado.respondedAt());
        var json = objectMapper.readTree(jdbc.queryForObject(
                "SELECT payload::text FROM outbox_encerramentos WHERE event_id = ?", String.class,
                resultado.responseEventId()));
        assertThat(json.path("eventId").asText()).isEqualTo(resultado.responseEventId().toString());
        assertThat(json.path("eventType").asText()).isEqualTo("EncerramentoTransacaoRespondido");
        assertThat(json.path("eventVersion").asInt()).isEqualTo(1);
        assertThat(json.path("occurredAt").asText()).isEqualTo(resultado.respondedAt().toString());
        assertThat(json.path("correlationId").asText()).isEqualTo(pedido.transactionId().toString());
        assertThat(json.path("causationId").asText()).isEqualTo(pedido.eventId().toString());
        assertThat(json.path("data").path("transactionId").asText()).isEqualTo(pedido.transactionId().toString());
        assertThat(json.path("data").path("creationEventId").asText()).isEqualTo(pedido.creationEventId().toString());
        assertThat(json.path("data").path("resultado").asText()).isEqualTo("CONFIRMADO");
        assertThat(json.path("data").has("outputEventId")).isFalse();
        assertThat(json.path("data").size()).isEqualTo(3);
    }

    @ParameterizedTest
    @CsvSource({"75.00,APROVADA", "101.00,REJEITADA"})
    void executar_deveResponderNegativaEPreservarVencedor_quandoDecisaoExistir(String valor, String status)
            throws Exception {
        var entrada = entrada(valor);
        var decisao = (ProcessamentoRegistrado) financeiro.executar(entrada);
        assertThat(decisao.status().name()).isEqualTo(status);
        var snapshot = jdbc.queryForMap("SELECT * FROM processamentos");
        var saida = jdbc.queryForMap("SELECT * FROM outbox_eventos");
        var pedido = pedido(entrada.transactionId(), entrada.eventId());

        var resultado = service.executar(pedido);

        assertThat(resultado.resultado()).isEqualTo(EncerramentoRegistrado.Resultado.DECISAO_EXISTENTE);
        assertThat(resultado.outputEventId()).contains(decisao.outputEventId());
        assertThat(jdbc.queryForMap("SELECT * FROM processamentos")).isEqualTo(snapshot);
        assertThat(jdbc.queryForMap("SELECT * FROM outbox_eventos")).isEqualTo(saida);
        assertThat(financeiro.executar(entrada)).isEqualTo(decisao);
        var registro = jdbc.queryForMap("SELECT * FROM encerramentos");
        assertThat(registro.get("resultado")).isEqualTo("DECISAO_EXISTENTE");
        assertThat(registro.get("output_event_id")).isEqualTo(decisao.outputEventId());
        var json = objectMapper.readTree(jdbc.queryForObject("SELECT payload::text FROM outbox_encerramentos", String.class));
        assertThat(json.path("causationId").asText()).isEqualTo(pedido.eventId().toString());
        assertThat(json.path("data").path("resultado").asText()).isEqualTo("DECISAO_EXISTENTE");
        assertThat(json.path("data").path("outputEventId").asText()).isEqualTo(decisao.outputEventId().toString());
        assertThat(json.path("data").size()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM criacao_bloqueada_recebimentos", Integer.class)).isZero();
    }

    @ParameterizedTest
    @EnumSource(IdentidadeDivergente.class)
    void executar_deveConflitarSemEscrita_quandoIdentidadeFinanceiraDivergir(IdentidadeDivergente campo) {
        var entrada = entrada("75.00");
        financeiro.executar(entrada);
        var snapshot = jdbc.queryForMap("SELECT * FROM processamentos");
        var saida = jdbc.queryForMap("SELECT * FROM outbox_eventos");
        var pedido = campo == IdentidadeDivergente.CRIACAO
                ? pedido(entrada.transactionId(), UUID.randomUUID())
                : pedido(UUID.randomUUID(), entrada.eventId());

        assertThatThrownBy(() -> service.executar(pedido))
                .isInstanceOf(ConflitoProcessamentoException.class)
                .hasMessage("identidade do encerramento divergente");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM encerramentos", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_encerramentos", Integer.class)).isZero();
        assertThat(jdbc.queryForMap("SELECT * FROM processamentos")).isEqualTo(snapshot);
        assertThat(jdbc.queryForMap("SELECT * FROM outbox_eventos")).isEqualTo(saida);
    }

    private enum IdentidadeDivergente { CRIACAO, TRANSACAO }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void executar_deveReutilizarRespostaSemNovosMetadados_quandoPedidoForEquivalente(boolean comDecisao) {
        var entrada = entrada("75.00");
        if (comDecisao) {
            financeiro.executar(entrada);
        }
        var pedido = pedido(entrada.transactionId(), entrada.eventId());
        var primeiro = service.executar(pedido);
        if (!comDecisao) {
            assertThat(financeiro.executar(entrada))
                    .isEqualTo(new CriacaoBloqueadaRegistrada(entrada, pedido.eventId()));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM processamentos", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_eventos", Integer.class)).isZero();
        }
        jdbc.update("UPDATE outbox_encerramentos SET published_at = TIMESTAMPTZ '2026-10-14 12:01:00Z'");
        var snapshot = jdbc.queryForMap("SELECT * FROM encerramentos");
        var saida = jdbc.queryForMap("SELECT * FROM outbox_encerramentos");
        var chamadasRelogio = relogio.chamadas.get();
        var chamadasGerador = gerador.chamadas.get();
        relogio.instante.set(Instant.parse("2026-10-16T15:00:00Z"));
        var equivalente = new PedidoEncerramentoRecebido(pedido.eventId(), pedido.transactionId(),
                pedido.creationEventId(), OffsetDateTime.parse("2026-10-14T09:00:00.123456789-03:00").toInstant(),
                pedido.correlationId(), pedido.motivo(), pedido.evidenceRef());

        var replay = service.executar(equivalente);

        assertThat(replay).isEqualTo(primeiro);
        assertThat(relogio.chamadas.get()).isEqualTo(chamadasRelogio);
        assertThat(gerador.chamadas.get()).isEqualTo(chamadasGerador);
        assertThat(jdbc.queryForMap("SELECT * FROM encerramentos")).isEqualTo(snapshot);
        assertThat(jdbc.queryForMap("SELECT * FROM outbox_encerramentos")).isEqualTo(saida);
    }

    @ParameterizedTest
    @EnumSource(PedidoDivergente.class)
    void executar_deveConflitarEPreservarPedidoOriginal_quandoPedidoDivergir(PedidoDivergente campo) {
        var pedido = pedido(UUID.randomUUID(), UUID.randomUUID());
        service.executar(pedido);
        var snapshot = jdbc.queryForMap("SELECT * FROM encerramentos");
        var saida = jdbc.queryForMap("SELECT * FROM outbox_encerramentos");
        var chamadasRelogio = relogio.chamadas.get();
        var chamadasGerador = gerador.chamadas.get();
        var transacao = campo == PedidoDivergente.TRANSACAO ? UUID.randomUUID() : pedido.transactionId();
        var outro = new PedidoEncerramentoRecebido(
                campo == PedidoDivergente.NOVO_PEDIDO ? UUID.randomUUID() : pedido.eventId(), transacao,
                campo == PedidoDivergente.CRIACAO ? UUID.randomUUID() : pedido.creationEventId(),
                campo == PedidoDivergente.INSTANTE ? pedido.occurredAt().plusNanos(1) : pedido.occurredAt(),
                transacao, pedido.motivo(),
                campo == PedidoDivergente.EVIDENCIA ? UUID.randomUUID() : pedido.evidenceRef());

        assertThatThrownBy(() -> service.executar(outro))
                .isInstanceOf(ConflitoProcessamentoException.class).hasMessage("pedido de encerramento divergente");

        assertThat(relogio.chamadas.get()).isEqualTo(chamadasRelogio);
        assertThat(gerador.chamadas.get()).isEqualTo(chamadasGerador);
        assertThat(jdbc.queryForMap("SELECT * FROM encerramentos")).isEqualTo(snapshot);
        assertThat(jdbc.queryForMap("SELECT * FROM outbox_encerramentos")).isEqualTo(saida);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processamentos", Integer.class)).isZero();
    }

    private enum PedidoDivergente { NOVO_PEDIDO, TRANSACAO, CRIACAO, INSTANTE, EVIDENCIA }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void executar_deveReverterRegistroERespostaEPermitirRetry_quandoSegundaEscritaFalhar(boolean comDecisao) {
        var entrada = entrada("75.00");
        if (comDecisao) {
            financeiro.executar(entrada);
        }
        var financeiros = jdbc.queryForList("SELECT * FROM processamentos");
        var saidasFinanceiras = jdbc.queryForList("SELECT * FROM outbox_eventos");
        var pedido = pedido(entrada.transactionId(), entrada.eventId());
        outboxControlada.falhar.set(true);

        assertThatThrownBy(() -> service.executar(pedido))
                .isInstanceOf(IllegalStateException.class).hasMessage("falha apos resposta de encerramento");

        assertThat(contar("encerramentos")).isZero();
        assertThat(contar("outbox_encerramentos")).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM processamentos")).isEqualTo(financeiros);
        assertThat(jdbc.queryForList("SELECT * FROM outbox_eventos")).isEqualTo(saidasFinanceiras);
        outboxControlada.falhar.set(false);
        var retry = service.executar(pedido);
        assertThat(retry.resultado()).isEqualTo(comDecisao
                ? EncerramentoRegistrado.Resultado.DECISAO_EXISTENTE : EncerramentoRegistrado.Resultado.CONFIRMADO);
        assertThat(contar("encerramentos")).isEqualTo(1);
        assertThat(contar("outbox_encerramentos")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void executar_deveObservarFinanceiroCommitadoOuRollback_quandoFinanceiroChegarPrimeiro(boolean falhar)
            throws Exception {
        var entrada = entrada("75.00");
        var pedido = pedido(entrada.transactionId(), entrada.eventId());

        var disputa = disputar(Etapa.FINANCEIRO, falhar,
                () -> financeiro.executar(entrada), () -> service.executar(pedido));

        comprovarPrimeira(disputa, falhar);
        assertThat(disputa.segunda().erro()).isNull();
        var resposta = (EncerramentoRegistrado) disputa.segunda().resultado();
        if (falhar) {
            assertThat(resposta.resultado()).isEqualTo(EncerramentoRegistrado.Resultado.CONFIRMADO);
            assertThat(resposta.outputEventId()).isEmpty();
            assertThat(contar("processamentos")).isZero();
            assertThat(contar("outbox_eventos")).isZero();
            assertThat(financeiro.executar(entrada)).isEqualTo(new CriacaoBloqueadaRegistrada(entrada, pedido.eventId()));
        } else {
            var decisao = (ProcessamentoRegistrado) disputa.primeira().resultado();
            assertThat(resposta.resultado()).isEqualTo(EncerramentoRegistrado.Resultado.DECISAO_EXISTENTE);
            assertThat(resposta.outputEventId()).contains(decisao.outputEventId());
            assertThat(financeiro.executar(entrada)).isEqualTo(decisao);
            assertThat(contar("processamentos")).isEqualTo(1);
            assertThat(contar("outbox_eventos")).isEqualTo(1);
        }
        assertThat(contar("encerramentos")).isEqualTo(1);
        assertThat(contar("outbox_encerramentos")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void executar_deveBloquearOuLiberarFinanceiro_quandoEncerramentoChegarPrimeiro(boolean falhar)
            throws Exception {
        var entrada = entrada("75.00");
        var pedido = pedido(entrada.transactionId(), entrada.eventId());

        var disputa = disputar(Etapa.ENCERRAMENTO, falhar,
                () -> service.executar(pedido), () -> financeiro.executar(entrada));

        comprovarPrimeira(disputa, falhar);
        assertThat(disputa.segunda().erro()).isNull();
        if (falhar) {
            assertThat(disputa.segunda().resultado()).isInstanceOf(ProcessamentoRegistrado.class);
            assertThat(contar("encerramentos")).isZero();
            assertThat(contar("outbox_encerramentos")).isZero();
            assertThat(contar("processamentos")).isEqualTo(1);
            assertThat(contar("outbox_eventos")).isEqualTo(1);
            assertThat(contar("criacao_bloqueada_recebimentos")).isZero();
        } else {
            assertThat(((EncerramentoRegistrado) disputa.primeira().resultado()).resultado())
                    .isEqualTo(EncerramentoRegistrado.Resultado.CONFIRMADO);
            assertThat(disputa.segunda().resultado()).isEqualTo(new CriacaoBloqueadaRegistrada(entrada, pedido.eventId()));
            assertThat(contar("encerramentos")).isEqualTo(1);
            assertThat(contar("outbox_encerramentos")).isEqualTo(1);
            assertThat(contar("processamentos")).isZero();
            assertThat(contar("outbox_eventos")).isZero();
            assertThat(contar("criacao_bloqueada_recebimentos")).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void executar_devePreservarPedidoVencedor_quandoPedidosConcorrerem(boolean equivalente) throws Exception {
        var pedido = pedido(UUID.randomUUID(), UUID.randomUUID());
        var outro = new PedidoEncerramentoRecebido(equivalente ? pedido.eventId() : UUID.randomUUID(),
                pedido.transactionId(), pedido.creationEventId(), pedido.occurredAt(), pedido.correlationId(),
                pedido.motivo(), pedido.evidenceRef());

        var disputa = disputar(Etapa.ENCERRAMENTO, false,
                () -> service.executar(pedido), () -> service.executar(outro));

        assertThat(disputa.primeira().erro()).isNull();
        var vencedor = (EncerramentoRegistrado) disputa.primeira().resultado();
        if (equivalente) {
            assertThat(disputa.segunda().erro()).isNull();
            assertThat(disputa.segunda().resultado()).isEqualTo(vencedor);
        } else {
            assertThat(disputa.segunda().erro()).isInstanceOf(ConflitoProcessamentoException.class)
                    .hasMessage("pedido de encerramento divergente");
        }
        assertThat(contar("encerramentos")).isEqualTo(1);
        assertThat(contar("outbox_encerramentos")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT response_event_id FROM encerramentos", UUID.class))
                .isEqualTo(vencedor.responseEventId());
        assertThat(jdbc.queryForObject("SELECT event_id FROM outbox_encerramentos", UUID.class))
                .isEqualTo(vencedor.responseEventId());
        assertThat(relogio.chamadas.get()).isEqualTo(1);
        assertThat(gerador.chamadas.get()).isEqualTo(1);
    }

    private int contar(String tabelaDaFixture) {
        return jdbc.queryForObject("SELECT count(*) FROM " + tabelaDaFixture, Integer.class);
    }

    private void comprovarPrimeira(Disputa disputa, boolean falhar) {
        if (falhar) {
            assertThat(disputa.primeira().erro()).isInstanceOf(IllegalStateException.class)
                    .hasMessage("falha concorrente controlada");
        } else {
            assertThat(disputa.primeira().erro()).isNull();
        }
    }

    private Disputa disputar(Etapa etapa, boolean falhar, Supplier<?> primeiraChamada, Supplier<?> segundaChamada)
            throws Exception {
        var porta = controle.armar(etapa, falhar);
        var executor = Executors.newFixedThreadPool(2);
        Future<?> primeira = null;
        Future<?> segunda = null;
        try {
            primeira = executor.submit(primeiraChamada::get);
            assertThat(porta.entrou().await(10, TimeUnit.SECONDS)).isTrue();
            segunda = executor.submit(segundaChamada::get);
            aguardarLockEmEspera();
            porta.liberar().countDown();
            return new Disputa(concluir(primeira), concluir(segunda));
        } finally {
            porta.liberar().countDown();
            if (primeira != null && !primeira.isDone()) { primeira.cancel(true); }
            if (segunda != null && !segunda.isDone()) { segunda.cancel(true); }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS))
                    .withFailMessage("workers do encerramento nao terminaram apos cleanup").isTrue();
        }
    }

    private Chamada concluir(Future<?> futuro) throws Exception {
        try {
            return new Chamada(futuro.get(10, TimeUnit.SECONDS), null);
        } catch (ExecutionException exception) {
            return new Chamada(null, exception.getCause());
        }
    }

    private void aguardarLockEmEspera() {
        var fim = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < fim) {
            Integer aguardando = jdbc.queryForObject("""
                    SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted
                      AND database = (SELECT oid FROM pg_database WHERE datname = current_database())
                    """, Integer.class);
            if (aguardando != null && aguardando > 0) { return; }
            LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
        }
        throw new AssertionError("segunda chamada nao aguardou lock do encerramento");
    }

    private record Chamada(Object resultado, Throwable erro) { }
    private record Disputa(Chamada primeira, Chamada segunda) { }
    private enum Etapa { FINANCEIRO, ENCERRAMENTO }

    static final class DisputaControlada {
        private final AtomicReference<Porta> atual = new AtomicReference<>();

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
                    throw new IllegalStateException("fixture nao liberou commit controlado");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("fixture concorrente interrompida", exception);
            }
            if (porta.falhar()) { throw new IllegalStateException("falha concorrente controlada"); }
        }
    }

    private record Porta(Etapa etapa, boolean falhar, CountDownLatch entrou, CountDownLatch liberar) { }

    private TransacaoCriadaRecebida entrada(String valor) {
        var transacao = UUID.randomUUID();
        return new TransacaoCriadaRecebida(UUID.randomUUID(), transacao,
                Instant.parse("2026-10-14T12:00:00.123456789Z"), transacao,
                new BigDecimal(valor), Currency.getInstance("BRL"));
    }

    private PedidoEncerramentoRecebido pedido(UUID transacao, UUID criacao) {
        return new PedidoEncerramentoRecebido(UUID.randomUUID(), transacao, criacao,
                Instant.parse("2026-10-14T12:00:00.123456789Z"), transacao,
                PedidoEncerramentoRecebido.Motivo.FALHA_PERMANENTE_CONTROLADA, UUID.randomUUID());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RelogioControlado {
        @Bean @Primary RelogioContado relogioEncerramento() { return new RelogioContado(); }
        @Bean @Primary GeradorContado geradorEncerramento() { return new GeradorContado(); }
        @Bean DisputaControlada disputaEncerramento() { return new DisputaControlada(); }

        @Bean @Primary OutboxEncerramentoControlada outboxEncerramentoControlada(
                @Qualifier("encerramentoJdbcRepository") OutboxEncerramentoRepository delegate, DisputaControlada controle) {
            return new OutboxEncerramentoControlada(delegate, controle);
        }

        @Bean @Primary OutboxFinanceiraControlada outboxFinanceiraControlada(
                @Qualifier("outboxProcessamentoJdbcRepository") OutboxProcessamentoRepository delegate,
                DisputaControlada controle) {
            return new OutboxFinanceiraControlada(delegate, controle);
        }
    }

    static final class OutboxEncerramentoControlada implements OutboxEncerramentoRepository {
        private final OutboxEncerramentoRepository delegate;
        private final DisputaControlada controle;
        private final AtomicBoolean falhar = new AtomicBoolean();

        OutboxEncerramentoControlada(OutboxEncerramentoRepository delegate, DisputaControlada controle) {
            this.delegate = delegate;
            this.controle = controle;
        }

        @Override public void adicionar(EventoSaidaPendente evento) {
            delegate.adicionar(evento);
            controle.segurar(Etapa.ENCERRAMENTO);
            if (falhar.get()) { throw new IllegalStateException("falha apos resposta de encerramento"); }
        }
    }

    static final class OutboxFinanceiraControlada implements OutboxProcessamentoRepository {
        private final OutboxProcessamentoRepository delegate;
        private final DisputaControlada controle;

        OutboxFinanceiraControlada(OutboxProcessamentoRepository delegate, DisputaControlada controle) {
            this.delegate = delegate;
            this.controle = controle;
        }

        @Override public void adicionar(EventoSaidaPendente evento) {
            delegate.adicionar(evento);
            controle.segurar(Etapa.FINANCEIRO);
        }
        @Override public Optional<EventoSaidaPendente> buscarPorEventId(UUID id) { return delegate.buscarPorEventId(id); }
        @Override public List<EventoSaidaPendente> buscarPendentes(int limite) { return delegate.buscarPendentes(limite); }
        @Override public void marcarPublicado(UUID id, Instant instante) { delegate.marcarPublicado(id, instante); }
    }

    static final class RelogioContado extends Clock {
        private final AtomicInteger chamadas = new AtomicInteger();
        private final AtomicReference<Instant> instante = new AtomicReference<>(
                Instant.parse("2026-10-14T12:00:01.987654321Z"));
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instante.get(), zone); }
        @Override public Instant instant() { chamadas.incrementAndGet(); return instante.get(); }
    }

    static final class GeradorContado implements GeradorEventIdSaida {
        private final AtomicInteger chamadas = new AtomicInteger();
        @Override public UUID gerar() { chamadas.incrementAndGet(); return UUID.randomUUID(); }
    }
}
