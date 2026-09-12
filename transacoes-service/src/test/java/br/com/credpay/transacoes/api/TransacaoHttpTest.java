package br.com.credpay.transacoes.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class TransacaoHttpTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @ParameterizedTest
    @ValueSource(strings = {"10.00", "10"})
    void criar_deveRetornar201ComStatusPendente_quandoTransacaoForValida(String valor) throws Exception {
        var response = mockMvc.perform(post("/transacoes")
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
    }

    @ParameterizedTest
    @ValueSource(strings = {"10.00", "0", "", "   "})
    void criar_deveRetornar400_quandoValorForTexto(String valor) throws Exception {
        mockMvc.perform(post("/transacoes")
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
        mockMvc.perform(post("/transacoes")
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
        mockMvc.perform(post("/transacoes")
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
        mockMvc.perform(post("/transacoes")
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
    @ValueSource(strings = {"ZZZ", "", "brl", " BRL "})
    void criar_deveRetornar422_quandoCodigoDaMoedaForInvalido(String moeda) throws Exception {
        mockMvc.perform(post("/transacoes")
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
        mockMvc.perform(post("/transacoes")
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
}
