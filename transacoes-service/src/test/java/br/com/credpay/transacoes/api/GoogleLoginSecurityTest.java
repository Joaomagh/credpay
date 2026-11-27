package br.com.credpay.transacoes.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import br.com.credpay.transacoes.application.CadastroContaExterna;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.util.UriComponentsBuilder;

@WebMvcTest(SessaoDemoController.class)
@Import({SessaoDemoConfiguration.class, LoginGoogleConfiguration.class})
@ActiveProfiles({"sessao-demo", "google-login"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class GoogleLoginSecurityTest {
    private static final AtomicLong MINUTO = new AtomicLong();

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private JdbcTemplate jdbc;
    @MockitoBean(name = "clock")
    private Clock relogio;
    @MockitoBean
    private ClientRegistrationRepository registros;
    @MockitoBean
    private CadastroContaExterna cadastro;

    @BeforeEach
    void preparar() {
        when(relogio.instant()).thenReturn(Instant.parse("2026-11-27T00:00:00Z")
                .plusSeconds(MINUTO.incrementAndGet() * 60));
        when(registros.findByRegistrationId("google")).thenReturn(CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId("cliente-ficticio").clientSecret("segredo-ficticio-nao-real")
                .scope("openid").redirectUri("http://localhost/auth/retorno/google")
                .clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(true).build()).build());
    }

    @Test
    void metodos_deveIndicarGoogleDisponivelSemExporConfiguracao() throws Exception {
        mvc.perform(get("/auth/metodos")).andExpect(status().isOk())
                .andExpect(jsonPath("$.google").value(true))
                .andExpect(jsonPath("$.clientId").doesNotExist())
                .andExpect(jsonPath("$.clientSecret").doesNotExist());
    }

    @Test
    void iniciar_deveRedirecionarComPkceEstadoENonce_semSegredoOuPermissoesAdicionais() throws Exception {
        var resposta = mvc.perform(get("/auth/externo/google")).andExpect(status().isFound()).andReturn();
        var destino = URI.create(resposta.getResponse().getHeader("Location"));
        var parametros = UriComponentsBuilder.fromUri(destino).build().getQueryParams();

        assertThat(destino.getScheme()).isEqualTo("https");
        assertThat(destino.getHost()).isEqualTo("accounts.google.com");
        assertThat(parametros.getFirst("response_type")).isEqualTo("code");
        assertThat(parametros.getFirst("scope")).isEqualTo("openid");
        assertThat(parametros.getFirst("state")).isNotBlank();
        assertThat(parametros.getFirst("nonce")).isNotBlank();
        assertThat(parametros.getFirst("code_challenge_method")).isEqualTo("S256");
        assertThat(parametros.getFirst("code_challenge")).matches("[A-Za-z0-9_-]{43}");
        assertThat(parametros).doesNotContainKeys("client_secret", "access_token", "id_token");
    }
}
