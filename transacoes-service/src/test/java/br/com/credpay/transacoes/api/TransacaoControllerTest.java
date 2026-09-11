package br.com.credpay.transacoes.api;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

import br.com.credpay.transacoes.application.CriarTransacao;
import br.com.credpay.transacoes.application.CriarTransacao.Resultado;
import br.com.credpay.transacoes.domain.StatusTransacao;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TransacaoController.class)
class TransacaoControllerTest {

    private static final UUID TRANSACAO_ID = UUID.fromString("7b8b61c2-9f63-4d74-9f8d-89cb52de0ed9");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CriarTransacao criarTransacao;

    @Test
    void deveCriarTransacaoPendente() throws Exception {
        var valor = new BigDecimal("10.00");
        var moeda = Currency.getInstance("BRL");

        when(criarTransacao.executar(valor, moeda))
                .thenReturn(new Resultado(TRANSACAO_ID, valor, moeda, StatusTransacao.PENDENTE));

        mockMvc.perform(post("/transacoes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "valor": 10.00,
                                  "moeda": "BRL"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/transacoes/" + TRANSACAO_ID))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(TRANSACAO_ID.toString()))
                .andExpect(jsonPath("$.valor").value(10.00))
                .andExpect(jsonPath("$.moeda").value("BRL"))
                .andExpect(jsonPath("$.status").value("PENDENTE"));

        verify(criarTransacao).executar(valor, moeda);
    }
}
