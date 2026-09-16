package br.com.credpay.transacoes.api;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

import br.com.credpay.transacoes.application.BuscarTransacao;
import br.com.credpay.transacoes.application.ConflitoIdempotenciaException;
import br.com.credpay.transacoes.application.CriarTransacao;
import br.com.credpay.transacoes.application.CriarTransacao.Resultado;
import br.com.credpay.transacoes.application.TransacaoNaoEncontradaException;
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
    private static final UUID CHAVE_IDEMPOTENCIA = UUID.fromString("c9628f6d-ef49-43d1-b521-a2d7b47a6241");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CriarTransacao criarTransacao;

    @MockitoBean
    private BuscarTransacao buscarTransacao;

    @Test
    void deveCriarTransacaoPendente() throws Exception {
        var valor = new BigDecimal("10.00");
        var moeda = Currency.getInstance("BRL");

        when(criarTransacao.executar(CHAVE_IDEMPOTENCIA, valor, moeda))
                .thenReturn(new Resultado(TRANSACAO_ID, valor, moeda, StatusTransacao.PENDENTE));

        mockMvc.perform(post("/transacoes")
                        .header("Idempotency-Key", CHAVE_IDEMPOTENCIA)
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

        verify(criarTransacao).executar(CHAVE_IDEMPOTENCIA, valor, moeda);
    }

    @Test
    void deveRejeitarCriacaoSemChaveIdempotencia() throws Exception {
        mockMvc.perform(post("/transacoes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "valor": 10.00,
                                  "moeda": "BRL"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Requisição inválida"))
                .andExpect(jsonPath("$.detail").value("Idempotency-Key deve ser informado"));

        verifyNoInteractions(criarTransacao);
    }

    @Test
    void deveRejeitarChaveIdempotenciaMalformada() throws Exception {
        mockMvc.perform(post("/transacoes")
                        .header("Idempotency-Key", "nao-e-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "valor": 10.00,
                                  "moeda": "BRL"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Requisição inválida"))
                .andExpect(jsonPath("$.detail").value("Idempotency-Key deve ser um UUID válido"));

        verifyNoInteractions(criarTransacao);
    }

    @Test
    void deveRetornarConflito_quandoChaveForReutilizadaComOutroPayload() throws Exception {
        var valor = new BigDecimal("10.00");
        var moeda = Currency.getInstance("BRL");
        when(criarTransacao.executar(CHAVE_IDEMPOTENCIA, valor, moeda))
                .thenThrow(new ConflitoIdempotenciaException());

        mockMvc.perform(post("/transacoes")
                        .header("Idempotency-Key", CHAVE_IDEMPOTENCIA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "valor": 10.00,
                                  "moeda": "BRL"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Conflito de idempotência"))
                .andExpect(jsonPath("$.detail")
                        .value("chave de idempotência já utilizada com outro payload"));

        verify(criarTransacao).executar(CHAVE_IDEMPOTENCIA, valor, moeda);
    }

    @Test
    void deveBuscarTransacaoPorId() throws Exception {
        var valor = new BigDecimal("10.00");
        var moeda = Currency.getInstance("BRL");
        when(buscarTransacao.executar(TRANSACAO_ID))
                .thenReturn(new BuscarTransacao.Resultado(
                        TRANSACAO_ID, valor, moeda, StatusTransacao.PENDENTE));

        mockMvc.perform(get("/transacoes/{id}", TRANSACAO_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(TRANSACAO_ID.toString()))
                .andExpect(jsonPath("$.valor").value(10.00))
                .andExpect(jsonPath("$.moeda").value("BRL"))
                .andExpect(jsonPath("$.status").value("PENDENTE"));

        verify(buscarTransacao).executar(TRANSACAO_ID);
    }

    @Test
    void deveRetornarNaoEncontradaParaIdAusente() throws Exception {
        when(buscarTransacao.executar(TRANSACAO_ID))
                .thenThrow(new TransacaoNaoEncontradaException());

        mockMvc.perform(get("/transacoes/{id}", TRANSACAO_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Transação não encontrada"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("transação não encontrada"))
                .andExpect(jsonPath("$.instance").value("/transacoes/" + TRANSACAO_ID));

        verify(buscarTransacao).executar(TRANSACAO_ID);
    }

    @Test
    void deveRetornarRequisicaoInvalidaSemConsultar_quandoIdForMalformado() throws Exception {
        var idMalformado = "nao-e-uuid";

        mockMvc.perform(get("/transacoes/{id}", idMalformado))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Requisição inválida"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("id deve ser um UUID válido"))
                .andExpect(jsonPath("$.instance").value("/transacoes/" + idMalformado));

        verifyNoInteractions(buscarTransacao);
    }
}
