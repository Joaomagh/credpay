package br.com.credpay.transacoes.api;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import br.com.credpay.transacoes.application.Clientes;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ClienteController.class)
@Import({SessaoDemoConfiguration.class, SessaoDemoController.class})
@ActiveProfiles("sessao-demo")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ClienteSecurityTest {

    private static final UUID CONTA = UUID.fromString("0774a5ae-25d6-4b23-9980-70bdd6b44ab0");
    private static final UUID CLIENTE = UUID.fromString("7628da68-e2a7-401e-87b2-b922f24292a3");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @MockitoBean
    private Clientes clientes;

    @MockitoBean
    private JdbcTemplate jdbc;

    @MockitoBean(name = "clock")
    private Clock relogio;

    private static final AtomicLong MINUTO = new AtomicLong();

    @BeforeEach
    void novaJanela() {
        // O contexto de filtros é compartilhado; cada cenário recebe sua própria janela.
        when(relogio.instant()).thenReturn(Instant.parse("2026-11-26T12:00:00Z")
                .plusSeconds(MINUTO.incrementAndGet() * 60));
    }

    @Test
    void consultar_devePermitirSessaoAutenticada() throws Exception {
        when(clientes.listar(CONTA, 20, null)).thenReturn(new Clientes.Pagina(List.of(), null));

        mvc.perform(get("/clientes").session(sessao()))
                .andExpect(status().isOk());
    }

    @Test
    void cadastrar_devePermitirSessaoComCsrfValido() throws Exception {
        when(clientes.cadastrar(CONTA, "Cliente Exemplo"))
                .thenReturn(new Clientes.Resultado(CLIENTE, "Cliente Exemplo"));
        var sessao = sessao();

        mvc.perform(post("/clientes").session(sessao).header("X-CSRF-TOKEN", csrf(sessao))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"Cliente Exemplo\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    void cadastrar_deveRecusarCsrfAusente_antesDoCadastro() throws Exception {
        mvc.perform(post("/clientes").session(sessao())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"Cliente Exemplo\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(clientes);
    }

    @Test
    void consultar_deveExigirAutenticacao_antesDaConsulta() throws Exception {
        mvc.perform(get("/clientes")).andExpect(status().isUnauthorized());
        verifyNoInteractions(clientes);
    }

    @Test
    void cadastrar_deveConsumirCotaPorConta_eInformarTempoDeEspera() throws Exception {
        when(clientes.cadastrar(CONTA, "Cliente Exemplo"))
                .thenReturn(new Clientes.Resultado(CLIENTE, "Cliente Exemplo"));
        var sessao = sessao();
        var csrf = csrf(sessao);
        for (int tentativa = 0; tentativa < 12; tentativa++) {
            mvc.perform(post("/clientes").session(sessao).header("X-CSRF-TOKEN", csrf)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"Cliente Exemplo\"}"))
                    .andExpect(status().isCreated());
        }
        mvc.perform(post("/clientes").session(sessao).header("X-CSRF-TOKEN", csrf)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"Cliente Exemplo\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/clientes", "/clientes/7628da68-e2a7-401e-87b2-b922f24292a3"})
    void consultar_deveConsumirCotaPorConta_eInformarTempoDeEspera(String rota) throws Exception {
        when(clientes.listar(CONTA, 20, null)).thenReturn(new Clientes.Pagina(List.of(), null));
        when(clientes.buscar(CONTA, CLIENTE)).thenReturn(new Clientes.Resultado(CLIENTE, "Cliente Exemplo"));
        var sessao = sessao();
        for (int tentativa = 0; tentativa < 45; tentativa++) {
            mvc.perform(get(rota).session(sessao)).andExpect(status().isOk());
        }
        mvc.perform(get(rota).session(sessao))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"));
    }

    private MockHttpSession sessao() {
        var conta = new ContaDemoPrincipal(CONTA, "cliente-conta", "hash-ficticio");
        var autenticacao = UsernamePasswordAuthenticationToken.authenticated(conta, null, conta.getAuthorities());
        var sessao = new MockHttpSession();
        sessao.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
                new SecurityContextImpl(autenticacao));
        return sessao;
    }

    private String csrf(MockHttpSession sessao) throws Exception {
        var resposta = mvc.perform(get("/auth/csrf").session(sessao)).andExpect(status().isOk()).andReturn();
        return json.readTree(resposta.getResponse().getContentAsString()).path("token").asText();
    }
}
