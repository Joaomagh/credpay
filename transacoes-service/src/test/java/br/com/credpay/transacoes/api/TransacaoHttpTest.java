package br.com.credpay.transacoes.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
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

    @Test
    void criar_deveRetornar422_quandoValorForZero() throws Exception {
        mockMvc.perform(post("/transacoes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"valor":0,"moeda":"BRL"}
                                """))
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
