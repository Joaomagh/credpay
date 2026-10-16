package br.com.credpay.transacoes.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Currency;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import br.com.credpay.transacoes.domain.StatusTransacao;
import tools.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import({SolicitarEncerramentoService.class, AplicarEncerramentoService.class,
        AplicarEncerramentoIntegrationTest.Configuracao.class})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class AplicarEncerramentoIntegrationTest {

    private static HikariDataSource poolDaFixture;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgresDaFixture()
            .withDatabaseName("credpay_aplicar_encerramento").withUsername("test").withPassword("test");

    private static final class PostgresDaFixture extends PostgreSQLContainer {
        private PostgresDaFixture() {
            super("postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0");
        }

        @Override public void stop() {
            try {
                assertThat(poolDaFixture).isNotNull();
                assertThat(isRunning()).isTrue();
                assertThat(poolDaFixture.isClosed()).isTrue();
            } finally { super.stop(); }
        }
    }

    @DynamicPropertySource
    static void configurarBanco(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private SolicitarEncerramentoService solicitar;
    @Autowired private AplicarEncerramentoService aplicar;
    @Autowired private BuscarTransacao buscar;
    @Autowired private TransicaoRepository historico;
    @Autowired private RespostaEncerramentoRepository respostas;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private HikariDataSource dataSource;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private RelogioContado clock;
    @Autowired private CriarTransacao criar;
    @Autowired private AplicarResultadoService financeiro;
    @Autowired private Controle controle;

    @BeforeEach void preparar() {
        poolDaFixture = dataSource;
        clock.instante = Instant.parse("2026-10-14T18:30:00.987654321Z");
        clock.chamadas.set(0);
        controle.desarmar();
        controle.falhar.set(null);
        jdbc.execute("""
                TRUNCATE respostas_encerramento_recebidas, outbox_pedidos_encerramento, pedidos_encerramento,
                  historico_transacoes, outbox_eventos, idempotencias_transacao, transacoes
                """);
    }

    @Test
    void executar_deveAplicarConfirmacaoCausal_noHistoricoUnico_ePreservarPostOriginal() throws Exception {
        var chave = UUID.randomUUID();
        var corpo = "{\"valor\":123.450,\"moeda\":\"BRL\"}";
        var original = mvc.perform(post("/transacoes").header("Idempotency-Key", chave)
                .contentType(MediaType.APPLICATION_JSON).content(corpo)).andExpect(status().isCreated()).andReturn();
        var transactionId = UUID.fromString(mapper.readTree(original.getResponse().getContentAsString()).path("id").asText());
        var pedido = solicitar.executar(UUID.randomUUID(), transactionId, UUID.randomUUID());
        var criacao = jdbc.queryForMap("SELECT * FROM outbox_eventos");
        var intencao = jdbc.queryForMap("SELECT * FROM outbox_pedidos_encerramento");
        var resposta = new RespostaEncerramentoRecebida(UUID.randomUUID(), transactionId, pedido.creationEventId(),
                Instant.parse("2026-10-14T18:00:00.123456789Z"), transactionId, pedido.eventId(),
                RespostaEncerramentoRecebida.Resultado.CONFIRMADO, Optional.empty());
        clock.chamadas.set(0);

        var aplicada = aplicar.executar(resposta);

        assertThat(buscar.executar(transactionId).status()).isEqualTo(StatusTransacao.FALHOU);
        assertThat(respostas.buscarPorEvento(resposta.eventId())).contains(resposta);
        var transicao = historico.buscarPorEvento(resposta.eventId()).orElseThrow();
        assertThat(aplicada.resposta()).isEqualTo(resposta);
        assertThat(aplicada.historico()).contains(transicao);
        assertThat(transicao).isEqualTo(new TransicaoRecebida(resposta.eventId(), transactionId,
                "EncerramentoTransacaoRespondido", 1, transactionId, pedido.eventId(), StatusTransacao.PENDENTE,
                StatusTransacao.FALHOU, "processamento-service/EncerramentoTransacaoRespondido.v1",
                resposta.occurredAt(), clock.instante.truncatedTo(ChronoUnit.MICROS)));
        assertThat(clock.chamadas.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM historico_transacoes", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT * FROM outbox_eventos")).isEqualTo(criacao);
        assertThat(jdbc.queryForMap("SELECT * FROM outbox_pedidos_encerramento")).isEqualTo(intencao);
        mvc.perform(get("/transacoes/{id}", transactionId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FALHOU"))
                .andExpect(jsonPath("$.valor").value(123.450)).andExpect(jsonPath("$.moeda").value("BRL"));
        mvc.perform(post("/transacoes").header("Idempotency-Key", chave)
                .contentType(MediaType.APPLICATION_JSON).content(corpo)).andExpect(status().isCreated())
                .andExpect(header().string("Location", original.getResponse().getHeader("Location")))
                .andExpect(content().string(original.getResponse().getContentAsString()));
        var falha = catchThrowable(() -> jdbc.update(
                "DELETE FROM respostas_encerramento_recebidas WHERE event_id = ?", resposta.eventId()));
        assertThat(falha).isInstanceOf(DataIntegrityViolationException.class);
        var causa = ((DataIntegrityViolationException) falha).getMostSpecificCause();
        assertThat(causa).isInstanceOf(PSQLException.class);
        assertThat(((PSQLException) causa).getSQLState()).isEqualTo("23503");
        assertThat(((PSQLException) causa).getServerErrorMessage().getConstraint())
                .isEqualTo("fk_historico_resposta_confirmada");
    }

    @ParameterizedTest
    @ValueSource(strings = {"aplicada", "recebida"})
    void executar_deveDistinguirRecebidoDeAplicado_eRepetirSemNovoRelogio(String etapa) {
        var resposta = entrada(RespostaEncerramentoRecebida.Resultado.CONFIRMADO);
        if (etapa.equals("recebida")) { respostas.inserir(resposta); }
        else { aplicar.executar(resposta); }
        var antes = historico.buscarPorEvento(resposta.eventId());
        clock.chamadas.set(0);
        clock.instante = clock.instante.plusSeconds(100);

        var aplicada = aplicar.executar(resposta);

        assertThat(buscar.executar(resposta.transactionId()).status()).isEqualTo(StatusTransacao.FALHOU);
        assertThat(aplicada.resposta()).isEqualTo(resposta);
        assertThat(aplicada.historico()).isPresent();
        if (etapa.equals("aplicada")) {
            assertThat(aplicada.historico()).isEqualTo(antes);
            assertThat(clock.chamadas.get()).isZero();
        } else { assertThat(clock.chamadas.get()).isEqualTo(1); }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM historico_transacoes", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM respostas_encerramento_recebidas", Integer.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"antes_APROVADA", "depois_APROVADA", "antes_REJEITADA", "depois_REJEITADA"})
    void executar_deveReceberNegativaSemTransicao_ePreservarSaidaFinanceira(String cenario) {
        var resposta = entrada(RespostaEncerramentoRecebida.Resultado.DECISAO_EXISTENTE);
        var estado = StatusTransacao.valueOf(cenario.substring(cenario.indexOf('_') + 1));
        var processada = financeiro(resposta, estado, resposta.outputEventId().orElseThrow(), resposta.creationEventId());
        if (cenario.startsWith("depois")) { financeiro.executar(processada); }
        var transacao = jdbc.queryForMap("SELECT * FROM transacoes");
        var historicoAntes = jdbc.queryForList("SELECT * FROM historico_transacoes");
        clock.chamadas.set(0);

        var recebida = aplicar.executar(resposta);

        assertThat(recebida).isEqualTo(new AplicacaoEncerramento(resposta, Optional.empty()));
        assertThat(clock.chamadas.get()).isZero();
        assertThat(jdbc.queryForMap("SELECT * FROM transacoes")).isEqualTo(transacao);
        assertThat(jdbc.queryForList("SELECT * FROM historico_transacoes")).isEqualTo(historicoAntes);
        if (cenario.startsWith("antes")) { financeiro.executar(processada); }
        assertThat(buscar.executar(resposta.transactionId()).status()).isEqualTo(estado);
        var registro = historico.buscarPorEvento(processada.eventId());
        var chamadas = clock.chamadas.get();
        clock.instante = clock.instante.plusSeconds(100);
        assertThat(aplicar.executar(resposta)).isEqualTo(recebida);
        assertThat(clock.chamadas.get()).isEqualTo(chamadas);
        assertThat(historico.buscarPorEvento(processada.eventId())).isEqualTo(registro);
        assertThat(respostas.buscarPorEvento(resposta.eventId())).contains(resposta);
    }

    @ParameterizedTest
    @ValueSource(strings = {"transacao", "criacao", "pedido", "instante", "resultado", "evento"})
    void executar_deveRecusarRespostaDivergente_semAlterarAplicacaoOriginal(String campo) {
        var original = entrada(RespostaEncerramentoRecebida.Resultado.CONFIRMADO);
        var outra = entrada(RespostaEncerramentoRecebida.Resultado.CONFIRMADO);
        aplicar.executar(original);
        var historicoAntes = jdbc.queryForList("SELECT * FROM historico_transacoes");
        var recibo = jdbc.queryForMap("SELECT * FROM respostas_encerramento_recebidas");
        var transacoesAntes = jdbc.queryForList("SELECT * FROM transacoes ORDER BY id");
        var divergente = new RespostaEncerramentoRecebida(campo.equals("evento") ? UUID.randomUUID() : original.eventId(),
                campo.equals("transacao") ? outra.transactionId() : original.transactionId(),
                campo.equals("criacao") ? outra.creationEventId() : original.creationEventId(),
                campo.equals("instante") ? original.occurredAt().plusNanos(1) : original.occurredAt(),
                campo.equals("transacao") ? outra.transactionId() : original.correlationId(),
                campo.equals("pedido") ? outra.causationId() : original.causationId(),
                campo.equals("resultado") ? RespostaEncerramentoRecebida.Resultado.DECISAO_EXISTENTE : original.resultado(),
                campo.equals("resultado") ? Optional.of(UUID.randomUUID()) : Optional.empty());
        var chamadas = clock.chamadas.get();

        assertThatThrownBy(() -> aplicar.executar(divergente)).isInstanceOf(ConfirmacaoEncerramentoRecusadaException.class)
                .hasMessage("resposta de encerramento divergente");

        assertThat(clock.chamadas.get()).isEqualTo(chamadas);
        assertThat(jdbc.queryForList("SELECT * FROM historico_transacoes")).isEqualTo(historicoAntes);
        assertThat(jdbc.queryForMap("SELECT * FROM respostas_encerramento_recebidas")).isEqualTo(recibo);
        assertThat(jdbc.queryForList("SELECT * FROM transacoes ORDER BY id")).isEqualTo(transacoesAntes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pedido_desconhecido", "transacao", "criacao"})
    void executar_deveRecusarCausaIncompativel_antesDeEscrever(String campo) {
        var original = entrada(RespostaEncerramentoRecebida.Resultado.CONFIRMADO);
        var outra = entrada(RespostaEncerramentoRecebida.Resultado.CONFIRMADO);
        var invalida = new RespostaEncerramentoRecebida(original.eventId(),
                campo.equals("transacao") ? outra.transactionId() : original.transactionId(),
                campo.equals("criacao") ? outra.creationEventId() : original.creationEventId(), original.occurredAt(),
                campo.equals("transacao") ? outra.transactionId() : original.transactionId(),
                campo.equals("pedido_desconhecido") ? UUID.randomUUID() : original.causationId(), original.resultado(),
                Optional.empty());
        var transacoesAntes = jdbc.queryForList("SELECT * FROM transacoes ORDER BY id");
        clock.chamadas.set(0);

        assertThatThrownBy(() -> aplicar.executar(invalida)).isInstanceOf(ConfirmacaoEncerramentoRecusadaException.class)
                .hasMessage("pedido de encerramento desconhecido ou incompatível");

        assertThat(clock.chamadas.get()).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM respostas_encerramento_recebidas")).isEmpty();
        assertThat(jdbc.queryForList("SELECT * FROM historico_transacoes")).isEmpty();
        assertThat(jdbc.queryForList("SELECT * FROM transacoes ORDER BY id")).isEqualTo(transacoesAntes);
    }

    @ParameterizedTest
    @EnumSource(value = StatusTransacao.class, names = {"APROVADA", "REJEITADA"})
    void executar_devePreservarFinanceiroAnterior_quandoConfirmacaoPositivaChegar(StatusTransacao estado) {
        var resposta = entrada(RespostaEncerramentoRecebida.Resultado.CONFIRMADO);
        financeiro.executar(financeiro(resposta, estado, UUID.randomUUID(), resposta.creationEventId()));
        var antes = jdbc.queryForMap("SELECT * FROM transacoes");
        var registro = jdbc.queryForMap("SELECT * FROM historico_transacoes");

        assertThatThrownBy(() -> aplicar.executar(resposta)).isInstanceOf(ConfirmacaoEncerramentoRecusadaException.class)
                .hasMessage("estado terminal incompatível com confirmação");

        assertThat(jdbc.queryForMap("SELECT * FROM transacoes")).isEqualTo(antes);
        assertThat(jdbc.queryForMap("SELECT * FROM historico_transacoes")).isEqualTo(registro);
        assertThat(jdbc.queryForList("SELECT * FROM respostas_encerramento_recebidas")).isEmpty();
    }

    @Test
    void executar_deveRecusarNegativaContraditoria_comFinanceiroJaAplicado() {
        var resposta = entrada(RespostaEncerramentoRecebida.Resultado.DECISAO_EXISTENTE);
        financeiro.executar(financeiro(resposta, StatusTransacao.APROVADA, UUID.randomUUID(), resposta.creationEventId()));
        var antes = jdbc.queryForMap("SELECT * FROM transacoes");
        var registro = jdbc.queryForMap("SELECT * FROM historico_transacoes");

        assertThatThrownBy(() -> aplicar.executar(resposta)).isInstanceOf(ConfirmacaoEncerramentoRecusadaException.class)
                .hasMessage("saída financeira incompatível com encerramento");

        assertThat(jdbc.queryForMap("SELECT * FROM transacoes")).isEqualTo(antes);
        assertThat(jdbc.queryForMap("SELECT * FROM historico_transacoes")).isEqualTo(registro);
        assertThat(jdbc.queryForList("SELECT * FROM respostas_encerramento_recebidas")).isEmpty();
    }

    @Test
    void executar_deveValidarNegativaPreRecebida_contraFinanceiroAtual() {
        var resposta = entrada(RespostaEncerramentoRecebida.Resultado.DECISAO_EXISTENTE);
        financeiro.executar(financeiro(resposta, StatusTransacao.APROVADA, UUID.randomUUID(), resposta.creationEventId()));
        respostas.inserir(resposta);
        var antes = jdbc.queryForMap("SELECT * FROM transacoes");
        var registro = jdbc.queryForMap("SELECT * FROM historico_transacoes");
        clock.chamadas.set(0);

        assertThatThrownBy(() -> aplicar.executar(resposta)).isInstanceOf(ConfirmacaoEncerramentoRecusadaException.class)
                .hasMessage("saída financeira incompatível com encerramento");

        assertThat(clock.chamadas.get()).isZero();
        assertThat(jdbc.queryForMap("SELECT * FROM transacoes")).isEqualTo(antes);
        assertThat(jdbc.queryForMap("SELECT * FROM historico_transacoes")).isEqualTo(registro);
        assertThat(respostas.buscarPorEvento(resposta.eventId())).contains(resposta);
    }

    @ParameterizedTest
    @ValueSource(strings = {"evento", "criacao"})
    void financeiro_deveExigirSaidaECriacaoRegistradasNaNegativa(String campo) {
        var resposta = entrada(RespostaEncerramentoRecebida.Resultado.DECISAO_EXISTENTE);
        respostas.inserir(resposta);
        var criacao = resposta.creationEventId();
        if (campo.equals("criacao")) {
            criacao = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO outbox_eventos (event_id, aggregate_id, event_type, event_version, payload, occurred_at)
                    SELECT ?, aggregate_id, event_type, event_version,
                           jsonb_set(payload, '{eventId}', to_jsonb(CAST(? AS text))), occurred_at
                    FROM outbox_eventos WHERE event_id = ?
                    """, criacao, criacao.toString(), resposta.creationEventId());
        }
        var entrada = financeiro(resposta, StatusTransacao.APROVADA,
                campo.equals("evento") ? UUID.randomUUID() : resposta.outputEventId().orElseThrow(), criacao);

        assertThatThrownBy(() -> financeiro.executar(entrada)).isInstanceOf(ConflitoResultadoException.class);

        assertThat(buscar.executar(resposta.transactionId()).status()).isEqualTo(StatusTransacao.PENDENTE);
        assertThat(jdbc.queryForList("SELECT * FROM historico_transacoes")).isEmpty();
        assertThat(respostas.buscarPorEvento(resposta.eventId())).contains(resposta);
    }

    @Test
    void financeiro_deveRecusarDecisao_quandoConfirmacaoPositivaJaFoiRecebida() {
        var resposta = entrada(RespostaEncerramentoRecebida.Resultado.CONFIRMADO);
        respostas.inserir(resposta);
        var entrada = financeiro(resposta, StatusTransacao.APROVADA, UUID.randomUUID(), resposta.creationEventId());

        assertThatThrownBy(() -> financeiro.executar(entrada)).isInstanceOf(ConflitoResultadoException.class);

        assertThat(buscar.executar(resposta.transactionId()).status()).isEqualTo(StatusTransacao.PENDENTE);
        assertThat(jdbc.queryForList("SELECT * FROM historico_transacoes")).isEmpty();
        aplicar.executar(resposta);
        assertThat(buscar.executar(resposta.transactionId()).status()).isEqualTo(StatusTransacao.FALHOU);
    }

    private RespostaEncerramentoRecebida entrada(RespostaEncerramentoRecebida.Resultado resultado) {
        var transacao = criar.executar(UUID.randomUUID(), new BigDecimal("123.450"), Currency.getInstance("BRL"));
        var pedido = solicitar.executar(UUID.randomUUID(), transacao.id(), UUID.randomUUID());
        return new RespostaEncerramentoRecebida(UUID.randomUUID(), transacao.id(), pedido.creationEventId(),
                Instant.parse("2026-10-14T18:00:00.123456789Z"), transacao.id(), pedido.eventId(), resultado,
                resultado == RespostaEncerramentoRecebida.Resultado.DECISAO_EXISTENTE
                        ? Optional.of(UUID.randomUUID()) : Optional.empty());
    }

    private TransacaoProcessadaRecebida financeiro(RespostaEncerramentoRecebida resposta, StatusTransacao estado,
            UUID evento, UUID criacao) {
        return new TransacaoProcessadaRecebida(evento, resposta.transactionId(), resposta.occurredAt(),
                resposta.transactionId(), criacao, estado);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ausente", "negativa"})
    void banco_deveExigirReciboPositivo_paraHistoricoDeFalha(String cenario) {
        var resposta = entrada(cenario.equals("negativa") ? RespostaEncerramentoRecebida.Resultado.DECISAO_EXISTENTE
                : RespostaEncerramentoRecebida.Resultado.CONFIRMADO);
        if (cenario.equals("negativa")) { respostas.inserir(resposta); }

        var falha = catchThrowable(() -> jdbc.update("""
                INSERT INTO historico_transacoes
                  (event_id, transaction_id, event_type, event_version, correlation_id, causation_id,
                   estado_anterior, estado_final, origem, occurred_at_epoch_second, occurred_at_nano, aplicado_em)
                VALUES (?, ?, 'EncerramentoTransacaoRespondido', 1, ?, ?, 'PENDENTE', 'FALHOU',
                        'processamento-service/EncerramentoTransacaoRespondido.v1', ?, ?, CURRENT_TIMESTAMP)
                """, resposta.eventId(), resposta.transactionId(), resposta.correlationId(), resposta.causationId(),
                resposta.occurredAt().getEpochSecond(), resposta.occurredAt().getNano()));

        assertThat(falha).isInstanceOf(DataIntegrityViolationException.class);
        var causa = ((DataIntegrityViolationException) falha).getMostSpecificCause();
        assertThat(causa).isInstanceOf(PSQLException.class);
        assertThat(((PSQLException) causa).getSQLState()).isEqualTo("23503");
        assertThat(((PSQLException) causa).getServerErrorMessage().getConstraint())
                .isEqualTo("fk_historico_resposta_confirmada");
        assertThat(jdbc.queryForList("SELECT * FROM historico_transacoes")).isEmpty();
        assertThat(buscar.executar(resposta.transactionId()).status()).isEqualTo(StatusTransacao.PENDENTE);
    }

    @Test
    void executar_deveCompararSaidaFinanceira_noReplayDaNegativa() {
        var resposta = entrada(RespostaEncerramentoRecebida.Resultado.DECISAO_EXISTENTE);
        aplicar.executar(resposta);
        var divergente = new RespostaEncerramentoRecebida(resposta.eventId(), resposta.transactionId(),
                resposta.creationEventId(), resposta.occurredAt(), resposta.correlationId(), resposta.causationId(),
                resposta.resultado(), Optional.of(UUID.randomUUID()));

        assertThatThrownBy(() -> aplicar.executar(divergente)).isInstanceOf(ConfirmacaoEncerramentoRecusadaException.class)
                .hasMessage("resposta de encerramento divergente");

        assertThat(respostas.buscarPorEvento(resposta.eventId())).contains(resposta);
        assertThat(buscar.executar(resposta.transactionId()).status()).isEqualTo(StatusTransacao.PENDENTE);
        assertThat(jdbc.queryForList("SELECT * FROM historico_transacoes")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONFIRMADO_RECIBO", "CONFIRMADO_CONFIRMACAO", "DECISAO_EXISTENTE_RECIBO"})
    void executar_deveReverterEscritasReais_ePermitirRetry(String cenario) {
        boolean negativa = cenario.startsWith("DECISAO_EXISTENTE");
        var resposta = entrada(negativa ? RespostaEncerramentoRecebida.Resultado.DECISAO_EXISTENTE
                : RespostaEncerramentoRecebida.Resultado.CONFIRMADO);
        var antes = jdbc.queryForMap("SELECT * FROM transacoes");
        controle.falhar.set(cenario.endsWith("CONFIRMACAO") ? Etapa.CONFIRMACAO : Etapa.RECIBO);

        assertThatThrownBy(() -> aplicar.executar(resposta)).isInstanceOf(IllegalStateException.class)
                .hasMessage("falha após SQL controlado");

        assertThat(jdbc.queryForMap("SELECT * FROM transacoes")).isEqualTo(antes);
        assertThat(jdbc.queryForList("SELECT * FROM historico_transacoes")).isEmpty();
        assertThat(jdbc.queryForList("SELECT * FROM respostas_encerramento_recebidas")).isEmpty();
        controle.falhar.set(null);
        aplicar.executar(resposta);
        assertThat(respostas.buscarPorEvento(resposta.eventId())).contains(resposta);
        assertThat(buscar.executar(resposta.transactionId()).status())
                .isEqualTo(negativa ? StatusTransacao.PENDENTE : StatusTransacao.FALHOU);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM historico_transacoes", Integer.class)).isEqualTo(negativa ? 0 : 1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"financeiro_commit", "confirmacao_commit", "financeiro_rollback", "confirmacao_rollback"})
    void executar_devePreservarVencedorNaDisputaComFinanceiro(String cenario) throws Exception {
        var resposta = entrada(RespostaEncerramentoRecebida.Resultado.CONFIRMADO);
        var processada = financeiro(resposta, StatusTransacao.APROVADA, UUID.randomUUID(), resposta.creationEventId());
        boolean financeiroPrimeiro = cenario.startsWith("financeiro");
        boolean reverter = cenario.endsWith("rollback");
        Callable<?> confirmar = () -> aplicar.executar(resposta);
        Callable<?> decidir = () -> financeiro.executar(processada);

        var disputa = disputar(financeiroPrimeiro ? Etapa.FINANCEIRO : Etapa.CONFIRMACAO, reverter,
                financeiroPrimeiro ? decidir : confirmar, financeiroPrimeiro ? confirmar : decidir);

        if (reverter) {
            assertThat(disputa.primeira().erro()).isInstanceOf(IllegalStateException.class)
                    .hasMessage("rollback concorrente controlado");
            assertThat(disputa.segunda().erro()).isNull();
        } else {
            assertThat(disputa.primeira().erro()).isNull();
            assertThat(disputa.segunda().erro()).isInstanceOf(financeiroPrimeiro
                    ? ConfirmacaoEncerramentoRecusadaException.class : ConflitoResultadoException.class);
        }
        boolean falhou = financeiroPrimeiro == reverter;
        assertThat(buscar.executar(resposta.transactionId()).status())
                .isEqualTo(falhou ? StatusTransacao.FALHOU : StatusTransacao.APROVADA);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM historico_transacoes", Integer.class)).isEqualTo(1);
        assertThat(respostas.buscarPorEvento(resposta.eventId()).isPresent()).isEqualTo(falhou);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void executar_deveSerializarRespostasEquivalentesOuDivergentes(boolean equivalente) throws Exception {
        var resposta = entrada(RespostaEncerramentoRecebida.Resultado.CONFIRMADO);
        var segunda = new RespostaEncerramentoRecebida(equivalente ? resposta.eventId() : UUID.randomUUID(),
                resposta.transactionId(), resposta.creationEventId(), resposta.occurredAt(), resposta.correlationId(),
                resposta.causationId(), resposta.resultado(), resposta.outputEventId());
        clock.chamadas.set(0);

        var disputa = disputar(Etapa.CONFIRMACAO, false, () -> aplicar.executar(resposta), () -> aplicar.executar(segunda));

        assertThat(disputa.primeira().erro()).isNull();
        if (equivalente) {
            assertThat(disputa.segunda().erro()).isNull();
            assertThat(disputa.segunda().resultado()).isEqualTo(disputa.primeira().resultado());
        } else {
            assertThat(disputa.segunda().erro()).isInstanceOf(ConfirmacaoEncerramentoRecusadaException.class)
                    .hasMessage("resposta de encerramento divergente");
        }
        assertThat(clock.chamadas.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM historico_transacoes", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM respostas_encerramento_recebidas", Integer.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void executar_deveConferirNegativaAposEsperaRealPeloOutroCommit(boolean financeiroPrimeiro) throws Exception {
        var resposta = entrada(RespostaEncerramentoRecebida.Resultado.DECISAO_EXISTENTE);
        var processada = financeiro(resposta, StatusTransacao.APROVADA,
                financeiroPrimeiro ? resposta.outputEventId().orElseThrow() : UUID.randomUUID(), resposta.creationEventId());
        Callable<?> receber = () -> aplicar.executar(resposta);
        Callable<?> decidir = () -> financeiro.executar(processada);

        var disputa = disputar(financeiroPrimeiro ? Etapa.FINANCEIRO : Etapa.RECIBO, false,
                financeiroPrimeiro ? decidir : receber, financeiroPrimeiro ? receber : decidir);

        assertThat(disputa.primeira().erro()).isNull();
        if (financeiroPrimeiro) { assertThat(disputa.segunda().erro()).isNull(); }
        else { assertThat(disputa.segunda().erro()).isInstanceOf(ConflitoResultadoException.class); }
        assertThat(buscar.executar(resposta.transactionId()).status())
                .isEqualTo(financeiroPrimeiro ? StatusTransacao.APROVADA : StatusTransacao.PENDENTE);
        assertThat(respostas.buscarPorEvento(resposta.eventId())).contains(resposta);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM historico_transacoes", Integer.class))
                .isEqualTo(financeiroPrimeiro ? 1 : 0);
    }

    private Disputa disputar(Etapa etapa, boolean reverter, Callable<?> primeiraChamada, Callable<?> segundaChamada)
            throws Exception {
        var porta = controle.armar(etapa, reverter);
        var executor = Executors.newFixedThreadPool(2);
        Future<?> primeira = null;
        Future<?> segunda = null;
        try {
            primeira = executor.submit(primeiraChamada);
            assertThat(porta.entrou().await(10, TimeUnit.SECONDS)).isTrue();
            segunda = executor.submit(segundaChamada);
            var fim = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            boolean aguardou = false;
            while (System.nanoTime() < fim) {
                var quantidade = jdbc.queryForObject("""
                        SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted
                          AND database = (SELECT oid FROM pg_database WHERE datname = current_database())
                        """, Integer.class);
                if (quantidade != null && quantidade > 0) { aguardou = true; break; }
                LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
            }
            assertThat(aguardou).withFailMessage("segunda chamada não aguardou advisory lock").isTrue();
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
        try { return new Chamada(futuro.get(10, TimeUnit.SECONDS), null); }
        catch (ExecutionException exception) { return new Chamada(null, exception.getCause()); }
    }

    private enum Etapa { RECIBO, CONFIRMACAO, FINANCEIRO }
    private record Porta(Etapa etapa, boolean reverter, CountDownLatch entrou, CountDownLatch liberar) { }
    private record Chamada(Object resultado, Throwable erro) { }
    private record Disputa(Chamada primeira, Chamada segunda) { }

    static class Controle {
        private final AtomicReference<Porta> atual = new AtomicReference<>();
        private final AtomicReference<Etapa> falhar = new AtomicReference<>();
        Porta armar(Etapa etapa, boolean reverter) {
            var porta = new Porta(etapa, reverter, new CountDownLatch(1), new CountDownLatch(1));
            atual.set(porta);
            return porta;
        }
        void desarmar() {
            var porta = atual.getAndSet(null);
            if (porta != null) { porta.liberar().countDown(); }
        }
        void apos(Etapa etapa) {
            var porta = atual.get();
            if (porta != null && porta.etapa() == etapa) {
                porta.entrou().countDown();
                try {
                    if (!porta.liberar().await(30, TimeUnit.SECONDS)) { throw new IllegalStateException("fixture não liberou SQL"); }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("fixture interrompida", exception);
                }
                if (porta.reverter()) { throw new IllegalStateException("rollback concorrente controlado"); }
            }
            if (falhar.get() == etapa) { throw new IllegalStateException("falha após SQL controlado"); }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuracao {
        @Bean @Primary RelogioContado relogioContado() { return new RelogioContado(); }
        @Bean Controle controle() { return new Controle(); }
        @Bean @Primary RespostaEncerramentoRepository respostasControladas(
                @Qualifier("respostaEncerramentoJdbcRepository") RespostaEncerramentoRepository real, Controle controle) {
            return new RespostaEncerramentoRepository() {
                @Override public void inserir(RespostaEncerramentoRecebida resposta) {
                    real.inserir(resposta);
                    controle.apos(Etapa.RECIBO);
                }
                @Override public Optional<RespostaEncerramentoRecebida> buscarPorEvento(UUID evento) {
                    return real.buscarPorEvento(evento);
                }
                @Override public Optional<RespostaEncerramentoRecebida> buscarPorTransactionId(UUID transacao) {
                    return real.buscarPorTransactionId(transacao);
                }
            };
        }
        @Bean @Primary FalhaConfirmadaRepository falhasControladas(
                @Qualifier("transicaoJdbcRepository") FalhaConfirmadaRepository real, Controle controle) {
            return transicao -> {
                real.registrarFalhaConfirmada(transicao);
                controle.apos(Etapa.CONFIRMACAO);
            };
        }
        @Bean @Primary TransicaoRepository historicoControlado(
                @Qualifier("transicaoJdbcRepository") TransicaoRepository real, Controle controle) {
            return new TransicaoRepository() {
                @Override public void bloquearIdentidades(UUID evento, UUID transacao) { real.bloquearIdentidades(evento, transacao); }
                @Override public void registrar(TransicaoRecebida transicao) {
                    real.registrar(transicao);
                    controle.apos(Etapa.FINANCEIRO);
                }
                @Override public Optional<TransicaoRecebida> buscarPorEvento(UUID evento) { return real.buscarPorEvento(evento); }
                @Override public boolean existeCriacao(UUID causa, UUID transacao) { return real.existeCriacao(causa, transacao); }
            };
        }
    }

    static class RelogioContado extends Clock {
        private volatile Instant instante = Instant.parse("2026-10-14T18:30:00.987654321Z");
        private final AtomicInteger chamadas = new AtomicInteger();

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instante, zone); }
        @Override public Instant instant() { chamadas.incrementAndGet(); return instante; }
    }
}
