package br.com.credpay.transacoes.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import br.com.credpay.transacoes.application.CadastroContaExterna;
import br.com.credpay.transacoes.application.Clientes;
import br.com.credpay.transacoes.domain.IdentidadeExterna;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;

@WebMvcTest({SessaoDemoController.class, ClienteController.class})
@Import({SessaoDemoConfiguration.class, LoginGoogleConfiguration.class})
@ActiveProfiles({"sessao-demo", "google-login"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class GoogleOidcCallbackTest {
    private static final ProvedorOidcFixture PROVEDOR = new ProvedorOidcFixture();
    private static final UUID CONTA = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final AtomicLong MINUTO = new AtomicLong();
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @MockitoBean
    private JdbcTemplate jdbc;
    @MockitoBean(name = "clock")
    private Clock relogio;
    @MockitoBean
    private ClientRegistrationRepository registros;
    @MockitoBean
    private CadastroContaExterna cadastro;
    @MockitoBean
    private Clientes clientes;

    @BeforeEach
    void preparar() {
        PROVEDOR.reiniciar();
        when(relogio.instant()).thenReturn(Instant.parse("2026-11-27T00:00:00Z")
                .plusSeconds(MINUTO.incrementAndGet() * 60));
        when(registros.findByRegistrationId("google")).thenReturn(PROVEDOR.registro());
        when(cadastro.obterOuCriar(new IdentidadeExterna(PROVEDOR.origem(), "sub-a"))).thenReturn(CONTA);
    }

    @AfterAll
    static void encerrar() { PROVEDOR.close(); }

    @Test
    void retornoValido_deveValidarPkceERenovarSessao_semEntregarTokensAoConsole() throws Exception {
        var inicio = iniciar();
        var anterior = inicio.sessao().getId();
        mvc.perform(get(URI.create(inicio.retorno())).session(inicio.sessao()))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/entrar"));

        assertThat(inicio.sessao().getId()).isNotEqualTo(anterior);
        mvc.perform(get("/auth/sessao").session(inicio.sessao())).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(CONTA.toString())).andExpect(jsonPath("$.csrf").isNotEmpty())
                .andExpect(jsonPath("$.access_token").doesNotExist()).andExpect(jsonPath("$.id_token").doesNotExist());
        assertThat(PROVEDOR.trocas()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = ProvedorOidcFixture.Modo.class, names = "VALIDO", mode = EnumSource.Mode.EXCLUDE)
    void tokenInvalido_deveRecusarSessaoDepoisDaTrocaReal(ProvedorOidcFixture.Modo modo) throws Exception {
        PROVEDOR.modo(modo);
        var inicio = iniciar();
        mvc.perform(get(URI.create(inicio.retorno())).session(inicio.sessao()))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/entrar?falha=google"));

        mvc.perform(get("/auth/sessao").session(inicio.sessao())).andExpect(status().isUnauthorized());
        assertThat(PROVEDOR.trocas()).isEqualTo(1);
    }

    @Test
    void estadoInvalido_deveRecusarAntesDeTrocarCodigo() throws Exception {
        var inicio = iniciar();
        var retorno = UriComponentsBuilder.fromUriString(inicio.retorno()).replaceQueryParam("state", "divergente")
                .build(true).toUri();
        mvc.perform(get(retorno).session(inicio.sessao())).andExpect(status().isFound())
                .andExpect(redirectedUrl("/entrar?falha=google"));

        mvc.perform(get("/auth/sessao").session(inicio.sessao())).andExpect(status().isUnauthorized());
        assertThat(PROVEDOR.trocas()).isZero();
    }

    @Test
    void retornoRepetido_naoDeveTrocarCodigoNovamente() throws Exception {
        var inicio = iniciar();
        mvc.perform(get(URI.create(inicio.retorno())).session(inicio.sessao()))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/entrar"));
        mvc.perform(get(URI.create(inicio.retorno())).session(inicio.sessao()))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/entrar?falha=google"));
        assertThat(PROVEDOR.trocas()).isEqualTo(1);
    }

    @Test
    void sessaoGoogle_devePreservarCsrfEContaNaApiDeClientes() throws Exception {
        var inicio = iniciar();
        mvc.perform(get(URI.create(inicio.retorno())).session(inicio.sessao()))
                .andExpect(redirectedUrl("/entrar"));
        mvc.perform(get("/auth/sessao").session(inicio.sessao())).andExpect(status().isOk());
        var cliente = UUID.fromString("33333333-3333-4333-8333-333333333333");
        when(clientes.cadastrar(CONTA, "Cliente Exemplo")).thenReturn(new Clientes.Resultado(cliente, "Cliente Exemplo"));
        mvc.perform(post("/clientes").session(inicio.sessao()).contentType("application/json")
                .content("{\"nome\":\"Cliente Exemplo\"}")).andExpect(status().isForbidden());
        var resposta = mvc.perform(get("/auth/csrf").session(inicio.sessao())).andReturn().getResponse();
        var csrf = json.readTree(resposta.getContentAsString()).path("token").asText();
        mvc.perform(post("/clientes").session(inicio.sessao()).header("X-CSRF-TOKEN", csrf)
                .header("X-CredPay-Conta", CONTA).contentType("application/json")
                .content("{\"nome\":\"Cliente Exemplo\"}")).andExpect(status().isCreated());
    }

    private Inicio iniciar() throws Exception {
        var resposta = mvc.perform(get("/auth/externo/google")).andExpect(status().isFound()).andReturn();
        var sessao = (MockHttpSession) resposta.getRequest().getSession(false);
        assertThat(sessao).isNotNull();
        return new Inicio(sessao, PROVEDOR.autorizar(resposta.getResponse().getHeader("Location")));
    }

    private record Inicio(MockHttpSession sessao, String retorno) { }
}
