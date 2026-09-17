package br.com.credpay.transacoes.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

import br.com.credpay.transacoes.application.TransacaoRepository;
import br.com.credpay.transacoes.domain.StatusTransacao;
import br.com.credpay.transacoes.domain.Transacao;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TransacaoHttpTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            "postgres@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0")
            .withDatabaseName("credpay_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void configurarBanco(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransacaoRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void buscar_deveRetornarTransacaoPersistida_quandoIdExistir() throws Exception {
        var criacao = mockMvc.perform(postTransacoes()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "valor": 10.00,
                                  "moeda": "BRL"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse();
        var id = objectMapper.readTree(criacao.getContentAsByteArray()).get("id").asText();

        mockMvc.perform(get("/transacoes/{id}", id))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.valor").value(10.00))
                .andExpect(jsonPath("$.moeda").value("BRL"))
                .andExpect(jsonPath("$.status").value("PENDENTE"));
    }

    @Test
    void buscar_deveRetornar404_quandoIdNaoExistir() throws Exception {
        var id = UUID.fromString("7b8b61c2-9f63-4d74-9f8d-89cb52de0ed9");

        mockMvc.perform(get("/transacoes/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Transação não encontrada"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("transação não encontrada"))
                .andExpect(jsonPath("$.instance").value("/transacoes/" + id))
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void buscar_deveRetornar400_quandoIdForMalformado() throws Exception {
        var idMalformado = "nao-e-uuid";

        mockMvc.perform(get("/transacoes/{id}", idMalformado))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Requisição inválida"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("id deve ser um UUID válido"))
                .andExpect(jsonPath("$.instance").value("/transacoes/" + idMalformado))
                .andExpect(header().doesNotExist("Location"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.00", "10"})
    void criar_deveRetornar201ComStatusPendente_quandoTransacaoForValida(String valor) throws Exception {
        var response = mockMvc.perform(postTransacoes()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valor\":%s,\"moeda\":\"BRL\"}".formatted(valor)))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.valor").value(10.00))
                .andExpect(jsonPath("$.moeda").value("BRL"))
                .andExpect(jsonPath("$.status").value("PENDENTE"))
                .andReturn().getResponse();

        var id = objectMapper.readTree(response.getContentAsByteArray()).get("id").asText();
        assertThat(UUID.fromString(id).toString()).isEqualTo(id);
        assertThat(response.getHeader("Location")).isEqualTo("/transacoes/" + id);

        var transacoes = new TransactionTemplate(transactionManager);
        Optional<Transacao> encontrada = transacoes.execute(
                status -> repository.buscarPorId(UUID.fromString(id)));
        assertThat(encontrada).isPresent();
        var persistida = encontrada.orElseThrow();
        var valorEsperado = new BigDecimal(valor);
        assertThat(persistida.id()).isEqualTo(UUID.fromString(id));
        assertThat(persistida.valor()).isEqualByComparingTo(valorEsperado);
        assertThat(persistida.valor().scale()).isEqualTo(valorEsperado.scale());
        assertThat(persistida.moeda()).isEqualTo(Currency.getInstance("BRL"));
        assertThat(persistida.status()).isEqualTo(StatusTransacao.PENDENTE);
    }

    @Test
    void criar_deveRepetirRespostaOriginal_quandoChaveEPayloadForemRepetidos() throws Exception {
        var chave = UUID.randomUUID();
        var primeiraResposta = mockMvc.perform(postTransacoes(chave)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"valor":10.00,"moeda":"BRL"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse();

        var replay = mockMvc.perform(postTransacoes(chave)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"moeda":"BRL","valor":10}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse();

        assertThat(replay.getHeader("Location")).isEqualTo(primeiraResposta.getHeader("Location"));
        assertThat(replay.getContentAsString()).isEqualTo(primeiraResposta.getContentAsString());
        var transacaoId = UUID.fromString(
                objectMapper.readTree(primeiraResposta.getContentAsByteArray()).get("id").asText());
        assertThat(contarEventos(transacaoId)).isEqualTo(1);
    }

    @Test
    void criar_deveRetornar409_quandoChaveForReutilizadaComOutroPayload() throws Exception {
        var chave = UUID.randomUUID();
        var primeiraResposta = mockMvc.perform(postTransacoes(chave)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"valor":10.00,"moeda":"BRL"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse();

        mockMvc.perform(postTransacoes(chave)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"valor":20.00,"moeda":"BRL"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Conflito de idempotência"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail")
                        .value("chave de idempotência já utilizada com outro payload"))
                .andExpect(jsonPath("$.instance").value("/transacoes"))
                .andExpect(header().doesNotExist("Location"));

        var transacaoId = UUID.fromString(
                objectMapper.readTree(primeiraResposta.getContentAsByteArray()).get("id").asText());
        assertThat(contarEventos(transacaoId)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.00", "0", "", "   "})
    void criar_deveRetornar400_quandoValorForTexto(String valor) throws Exception {
        mockMvc.perform(postTransacoes()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valor\":\"%s\",\"moeda\":\"BRL\"}".formatted(valor)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Requisição inválida"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value(
                        "corpo deve conter um JSON válido com valor numérico e moeda textual"))
                .andExpect(jsonPath("$.instance").value("/transacoes"))
                .andExpect(header().doesNotExist("Location"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"moeda\":\"BRL\"}", "{\"valor\":null,\"moeda\":\"BRL\"}"})
    void criar_deveRetornar422_quandoValorForAusenteOuNulo(String request) throws Exception {
        mockMvc.perform(postTransacoes()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Transação inválida"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.detail").value("valor deve ser informado"))
                .andExpect(jsonPath("$.instance").value("/transacoes"))
                .andExpect(header().doesNotExist("Location"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "null",
            "{\"valor\":",
            "{\"valor\":{},\"moeda\":\"BRL\"}",
            "{\"valor\":10.00,\"moeda\":{}}"
    })
    void criar_deveRetornar400_quandoCorpoNaoPuderSerLido(String request) throws Exception {
        mockMvc.perform(postTransacoes()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Requisição inválida"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value(
                        "corpo deve conter um JSON válido com valor numérico e moeda textual"))
                .andExpect(jsonPath("$.instance").value("/transacoes"))
                .andExpect(header().doesNotExist("Location"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-0.01"})
    void criar_deveRetornar422_quandoValorForZeroOuNegativo(String valor) throws Exception {
        mockMvc.perform(postTransacoes()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valor\":%s,\"moeda\":\"BRL\"}".formatted(valor)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Transação inválida"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.detail").value("valor deve ser maior que zero"))
                .andExpect(jsonPath("$.instance").value("/transacoes"))
                .andExpect(header().doesNotExist("Location"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"123", "1.5", "true", "false"})
    void criar_deveRetornar400_quandoMoedaNaoForTexto(String moeda) throws Exception {
        mockMvc.perform(postTransacoes()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valor\":10.00,\"moeda\":%s}".formatted(moeda)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Requisição inválida"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value(
                        "corpo deve conter um JSON válido com valor numérico e moeda textual"))
                .andExpect(jsonPath("$.instance").value("/transacoes"))
                .andExpect(header().doesNotExist("Location"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ZZZ", "", "brl", " BRL "})
    void criar_deveRetornar422_quandoCodigoDaMoedaForInvalido(String moeda) throws Exception {
        mockMvc.perform(postTransacoes()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"valor\":10.00,\"moeda\":\"%s\"}".formatted(moeda)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Transação inválida"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.detail").value(
                        "moeda deve ser um código ISO 4217 válido em letras maiúsculas"))
                .andExpect(jsonPath("$.instance").value("/transacoes"))
                .andExpect(header().doesNotExist("Location"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"valor\":10.00}",
            "{\"valor\":10.00,\"moeda\":null}"
    })
    void criar_deveRetornar422_quandoMoedaForAusenteOuNula(String request) throws Exception {
        mockMvc.perform(postTransacoes()
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Transação inválida"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.detail").value("moeda deve ser informada"))
                .andExpect(jsonPath("$.instance").value("/transacoes"))
                .andExpect(header().doesNotExist("Location"));
    }

    private MockHttpServletRequestBuilder postTransacoes() {
        return postTransacoes(UUID.randomUUID());
    }

    private MockHttpServletRequestBuilder postTransacoes(UUID chaveIdempotencia) {
        return post("/transacoes").header("Idempotency-Key", chaveIdempotencia);
    }

    private int contarEventos(UUID transacaoId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_eventos WHERE aggregate_id = ?",
                Integer.class,
                transacaoId);
    }
}
