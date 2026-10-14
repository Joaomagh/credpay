package br.com.credpay.transacoes.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import br.com.credpay.transacoes.domain.StatusTransacao;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
