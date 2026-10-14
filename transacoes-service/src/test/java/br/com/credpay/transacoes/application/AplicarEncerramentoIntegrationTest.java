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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Currency;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import br.com.credpay.transacoes.domain.StatusTransacao;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Import({SolicitarEncerramentoService.class, AplicarEncerramentoService.class,
        AplicarEncerramentoIntegrationTest.Configuracao.class})
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class AplicarEncerramentoIntegrationTest {

    private static HikariDataSource poolDaFixture;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgresDaFixture()
            .withDatabaseName("credpay_aplicar_encerramento").withUsername("test").withPassword("test");

    private static final class PostgresDaFixture extends PostgreSQLContainer<PostgresDaFixture> {
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

    @BeforeEach void preparar() {
        poolDaFixture = dataSource;
        clock.instante = Instant.parse("2026-10-14T18:30:00.987654321Z");
        clock.chamadas.set(0);
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

    @TestConfiguration(proxyBeanMethods = false)
    static class Configuracao {
        @Bean @Primary RelogioContado relogioContado() { return new RelogioContado(); }
    }

    static class RelogioContado extends Clock {
        private volatile Instant instante = Instant.parse("2026-10-14T18:30:00.987654321Z");
        private final AtomicInteger chamadas = new AtomicInteger();

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instante, zone); }
        @Override public Instant instant() { chamadas.incrementAndGet(); return instante; }
    }
}
