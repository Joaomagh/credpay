package br.com.credpay.transacoes.api;

import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.List;

import br.com.credpay.transacoes.application.Clientes;
import br.com.credpay.transacoes.application.ClienteNaoEncontradoException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = ClienteController.class, excludeFilters = @ComponentScan.Filter(
        type = FilterType.ASSIGNABLE_TYPE, classes = SessaoDemoConfiguration.class))
@ActiveProfiles("sessao-demo")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class ClienteControllerTest {

    private static final UUID CONTA = UUID.fromString("f6289fb9-e481-4ae7-beb5-91dc18dc61c7");
    private static final UUID CLIENTE = UUID.fromString("7d7753d2-4516-435a-aea6-233a1c271cc4");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private Clientes clientes;

    @Test
    void cadastrar_deveRetornarClienteDaContaAutenticada_quandoNomeForValido() throws Exception {
        when(clientes.cadastrar(CONTA, "Cliente Exemplo"))
                .thenReturn(new Clientes.Resultado(CLIENTE, "Cliente Exemplo"));

        mvc.perform(post("/clientes").principal(autenticacao())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Cliente Exemplo\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/clientes/" + CLIENTE))
                .andExpect(jsonPath("$.id").value(CLIENTE.toString()))
                .andExpect(jsonPath("$.nome").value("Cliente Exemplo"))
                .andExpect(jsonPath("$.contaId").doesNotExist());
    }

    @Test
    void cadastrar_deveRecusarSessaoAusente_antesDeAcessarClientes() throws Exception {
        when(clientes.cadastrar(null, "Cliente Exemplo"))
                .thenReturn(new Clientes.Resultado(CLIENTE, "Cliente Exemplo"));

        mvc.perform(post("/clientes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Cliente Exemplo\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(clientes);
    }

    @Test
    void cadastrar_deveRecusarContextoDeOutraConta_antesDeAcessarClientes() throws Exception {
        when(clientes.cadastrar(CONTA, "Cliente Exemplo"))
                .thenReturn(new Clientes.Resultado(CLIENTE, "Cliente Exemplo"));

        mvc.perform(post("/clientes").principal(autenticacao())
                        .header("X-CredPay-Conta", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Cliente Exemplo\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(clientes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"contaId", "id", "outro"})
    void cadastrar_deveRecusarCampoExtra_semAceitarProprietarioDoCorpo(String campo) throws Exception {
        when(clientes.cadastrar(CONTA, "Cliente Exemplo"))
                .thenReturn(new Clientes.Resultado(CLIENTE, "Cliente Exemplo"));

        mvc.perform(post("/clientes").principal(autenticacao())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Cliente Exemplo\",\"" + campo + "\":\"extra\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(clientes);
    }

    @Test
    void cadastrar_deveImpedirCacheDoCliente() throws Exception {
        when(clientes.cadastrar(CONTA, "Cliente Exemplo"))
                .thenReturn(new Clientes.Resultado(CLIENTE, "Cliente Exemplo"));

        mvc.perform(post("/clientes").principal(autenticacao())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Cliente Exemplo\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cadastrar_deveLimitarCorpoMesmoSemComprimentoDeclarado(boolean comprimentoDeclarado) throws Exception {
        when(clientes.cadastrar(CONTA, "Cliente Exemplo"))
                .thenReturn(new Clientes.Resultado(CLIENTE, "Cliente Exemplo"));
        var corpo = "{\"nome\":\"Cliente Exemplo\"}";
        var bytes = (corpo + " ".repeat(8193 - corpo.length())).getBytes(java.nio.charset.StandardCharsets.UTF_8);

        mvc.perform(contexto -> {
            var request = new MockHttpServletRequest(contexto, "POST", "/clientes") {
                @Override
                public long getContentLengthLong() { return comprimentoDeclarado ? super.getContentLengthLong() : -1; }

                @Override
                public int getContentLength() { return comprimentoDeclarado ? super.getContentLength() : -1; }
            };
            request.setUserPrincipal(autenticacao());
            request.setContentType(MediaType.APPLICATION_JSON_VALUE);
            request.setContent(bytes);
            return request;
        }).andExpect(status().is(413));
        verifyNoInteractions(clientes);
    }

    private UsernamePasswordAuthenticationToken autenticacao() {
        var conta = new ContaDemoPrincipal(CONTA, "conta-exemplo", "hash-ficticio");
        return UsernamePasswordAuthenticationToken.authenticated(conta, null, conta.getAuthorities());
    }

    @Test
    void buscar_deveConsultarSomenteNaContaAtual_eImpedirCache() throws Exception {
        when(clientes.buscar(CONTA, CLIENTE)).thenReturn(new Clientes.Resultado(CLIENTE, "Cliente Exemplo"));

        mvc.perform(get("/clientes/" + CLIENTE).principal(autenticacao()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.id").value(CLIENTE.toString()))
                .andExpect(jsonPath("$.nome").value("Cliente Exemplo"))
                .andExpect(jsonPath("$.contaId").doesNotExist());
    }

    @Test
    void buscar_deveInformarAusencia_semDistinguirClienteAlheio() throws Exception {
        when(clientes.buscar(CONTA, CLIENTE)).thenThrow(new ClienteNaoEncontradoException());

        mvc.perform(get("/clientes/" + CLIENTE).principal(autenticacao()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("cliente não encontrado"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1-1-1-1-1", "nao-uuid", "7d7753d2-4516-435a-aea6-233a1c271cc4 "})
    void buscar_deveRecusarIdentidadeNaoCanonica_antesDaConsulta(String id) throws Exception {
        mvc.perform(get("/clientes/" + id).principal(autenticacao()))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(clientes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/clientes", "/clientes/7d7753d2-4516-435a-aea6-233a1c271cc4"})
    void consultar_deveRecusarSessaoAusente_antesDeAcessarClientes(String rota) throws Exception {
        mvc.perform(get(rota)).andExpect(status().isUnauthorized());
        verifyNoInteractions(clientes);
    }

    @Test
    void listar_deveUsarLimitePadrao_eInformarFimDaLista() throws Exception {
        when(clientes.listar(CONTA, 20, null)).thenReturn(
                new Clientes.Pagina(List.of(new Clientes.Resultado(CLIENTE, "Cliente Exemplo")), null));

        mvc.perform(get("/clientes").principal(autenticacao()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.itens.length()").value(1))
                .andExpect(jsonPath("$.itens[0].id").value(CLIENTE.toString()))
                .andExpect(jsonPath("$.proximo").isEmpty());
    }

    @Test
    void listar_deveAceitarCursorELimite_expondoProximaPagina() throws Exception {
        var proximo = UUID.fromString("bc118922-c318-494f-b7e6-f44e9f6299b6");
        when(clientes.listar(CONTA, 2, CLIENTE)).thenReturn(
                new Clientes.Pagina(List.of(new Clientes.Resultado(proximo, "Outro Cliente")), proximo));

        mvc.perform(get("/clientes").principal(autenticacao())
                        .param("limite", "2").param("apos", CLIENTE.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proximo").value(proximo.toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "51", "-1", "abc", "1.5", "", "2147483648"})
    void listar_deveRecusarLimiteInvalido_antesDaConsulta(String limite) throws Exception {
        mvc.perform(get("/clientes").principal(autenticacao()).param("limite", limite))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(clientes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1-1-1-1-1", "", "nao-uuid"})
    void listar_deveRecusarCursorInvalido_antesDaConsulta(String apos) throws Exception {
        mvc.perform(get("/clientes").principal(autenticacao()).param("apos", apos))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(clientes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"contaId", "pagina", "filtro"})
    void listar_deveRecusarParametroDesconhecido_semAlterarEscopo(String campo) throws Exception {
        mvc.perform(get("/clientes").principal(autenticacao()).param(campo, "extra"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(clientes);
    }

    @Test
    void listar_deveRecusarParametroRepetido_antesDaConsulta() throws Exception {
        mvc.perform(get("/clientes").principal(autenticacao()).param("limite", "1", "2"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(clientes);
    }
}
