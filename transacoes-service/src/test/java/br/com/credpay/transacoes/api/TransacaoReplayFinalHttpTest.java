package br.com.credpay.transacoes.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import br.com.credpay.transacoes.application.AplicarResultadoService;
import br.com.credpay.transacoes.application.TransacaoProcessadaRecebida;
import br.com.credpay.transacoes.application.TransicaoRepository;
import br.com.credpay.transacoes.domain.StatusTransacao;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
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
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
class TransacaoReplayFinalHttpTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            "postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0")
            .withDatabaseName("credpay_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void configurarBanco(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AplicarResultadoService aplicar;
    @Autowired private TransicaoRepository historico;

    @ParameterizedTest
    @EnumSource(value = StatusTransacao.class, names = {"APROVADA", "REJEITADA"})
    void criar_deveRepetirRespostaOriginalEnquantoConsultaConservaFinal(StatusTransacao estado) throws Exception {
        var chave = UUID.randomUUID();
        var original = mvc.perform(post("/transacoes").header("Idempotency-Key", chave)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"valor\":123.450,\"moeda\":\"BRL\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDENTE"))
                .andReturn().getResponse();
        var id = UUID.fromString(mapper.readTree(original.getContentAsByteArray()).get("id").asText());
        var causa = jdbc.queryForObject("SELECT event_id FROM outbox_eventos WHERE aggregate_id = ?", UUID.class, id);
        var outboxOriginal = jdbc.queryForMap("SELECT * FROM outbox_eventos WHERE event_id = ?", causa);
        var transicao = aplicar.executar(new TransacaoProcessadaRecebida(UUID.randomUUID(), id,
                Instant.parse("2026-10-03T12:00:00.123456789Z"), id, causa, estado));

        var replay = mvc.perform(post("/transacoes").header("Idempotency-Key", chave)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"moeda\":\"BRL\",\"valor\":123.45}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDENTE"))
                .andReturn().getResponse();

        assertThat(replay.getContentAsString()).withFailMessage("replay divergiu da resposta original")
                .isEqualTo(original.getContentAsString());
        assertThat(replay.getHeader("Location")).isEqualTo(original.getHeader("Location"));
        mvc.perform(get("/transacoes/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.status").value(estado.name()))
                .andExpect(jsonPath("$.valor").value(123.45))
                .andExpect(jsonPath("$.moeda").value("BRL"));
        assertThat(historico.buscarPorEvento(transicao.eventId())).contains(transicao);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM historico_transacoes WHERE transaction_id = ?",
                Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_eventos WHERE aggregate_id = ?",
                Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT scale(valor) FROM transacoes WHERE id = ?", Integer.class, id)).isEqualTo(3);
        assertThat(jdbc.queryForMap("SELECT * FROM outbox_eventos WHERE event_id = ?", causa))
                .withFailMessage("outbox de criação foi alterada pelo replay").isEqualTo(outboxOriginal);
    }
}
