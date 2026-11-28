package br.com.credpay.transacoes.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import br.com.credpay.transacoes.application.CadastroContaEmail;
import br.com.credpay.transacoes.domain.PedidoCadastroConta;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = RegistroContaController.class, excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE, classes = SessaoDemoConfiguration.class))
@ActiveProfiles("sessao-demo")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(JacksonConfiguration.class)
class RegistroContaControllerTest {
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private CadastroContaEmail cadastro;

    @Test
    void aceitaCadastroSemRetornarIdentidadeOuSenha() throws Exception {
        mvc.perform(post("/auth/registro").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"Pessoa@EXAMPLE.COM\",\"senha\":\"senha de ensaio\"}"))
                .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(""));
        verify(cadastro).cadastrar(new PedidoCadastroConta("pessoa@example.com", "senha de ensaio"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"email\":\"invalido\",\"senha\":\"senha de ensaio\"}",
            "{\"email\":\"pessoa@example.com\",\"senha\":\"curta\"}"})
    void validaPedidoAntesDeCadastrar(String corpo) throws Exception {
        mvc.perform(post("/auth/registro").contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isUnprocessableContent());
        verifyNoInteractions(cadastro);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "{\"email\":123,\"senha\":\"senha de ensaio\"}",
            "{\"email\":\"pessoa@example.com\",\"senha\":\"senha de ensaio\",\"contaId\":\"ignorar\"}"})
    void rejeitaJsonInvalidoCoercaoOuCamposExtras(String corpo) throws Exception {
        mvc.perform(post("/auth/registro").contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(cadastro);
    }

    @Test
    void rejeitaQuerySemUsarDadosNaUrl() throws Exception {
        mvc.perform(post("/auth/registro?email=nao-usar").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"pessoa@example.com\",\"senha\":\"senha de ensaio\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(cadastro);
    }

    @Test
    void limitaCorpoMesmoSemContentLength() throws Exception {
        mvc.perform(post("/auth/registro").contentType(MediaType.APPLICATION_JSON).content(" ".repeat(8193)))
                .andExpect(status().isContentTooLarge());
        verifyNoInteractions(cadastro);
    }
}
