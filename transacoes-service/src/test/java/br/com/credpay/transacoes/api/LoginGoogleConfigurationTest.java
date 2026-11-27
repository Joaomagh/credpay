package br.com.credpay.transacoes.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

class LoginGoogleConfigurationTest {
    private final LoginGoogleConfiguration configuracao = new LoginGoogleConfiguration();

    @Test
    void configurar_deveExigirCredenciaisSemRefletirValores() {
        assertThatThrownBy(() -> configuracao.registrosGoogle(new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class).hasMessage("Configuração Google inválida");
        assertThatThrownBy(() -> configuracao.registrosGoogle(new MockEnvironment()
                .withProperty("CREDPAY_GOOGLE_CLIENT_ID", "cliente-ficticio")))
                .isInstanceOf(IllegalStateException.class).hasMessage("Configuração Google inválida");
    }

    @Test
    void configurar_deveExigirPerfilDeSessao() {
        var ambiente = ambiente("https://credpay.example/auth/retorno/google", true);
        ambiente.setActiveProfiles("google-login");
        assertThatThrownBy(() -> configuracao.registrosGoogle(ambiente))
                .isInstanceOf(IllegalStateException.class).hasMessage("Configuração Google inválida");
    }

    @Test
    void configurar_deveUsarApenasOpenid_eExigirPkceNaOrigemExplicita() {
        var registros = configuracao.registrosGoogle(ambiente("https://credpay.example/auth/retorno/google", true));
        assertThat(registros).isNotNull();
        var google = registros.findByRegistrationId("google");
        assertThat(google).isNotNull();
        assertThat(google.getScopes()).containsExactly("openid");
        assertThat(google.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(google.getRedirectUri()).isEqualTo("https://credpay.example/auth/retorno/google");
        assertThat(google.getProviderDetails().getIssuerUri()).isEqualTo("https://accounts.google.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://credpay.example/auth/retorno/google",
            "http://localhost:4200/auth/retorno/google", "https://user@credpay.example/auth/retorno/google",
            "https://credpay.example/auth/retorno/google?x=1", "https://credpay.example/auth/retorno/google#x",
            "https://credpay.example/outro", "https://credpay.example:444/auth/retorno/google", "inválido"})
    void configurar_deveRecusarRetornoPublicoInseguro(String retorno) {
        assertThatThrownBy(() -> configuracao.registrosGoogle(ambiente(retorno, true)))
                .isInstanceOf(IllegalStateException.class).hasMessage("Configuração Google inválida");
    }

    @Test
    void configurar_devePermitirHttpSomenteNoLoopbackLocal() {
        var registros = configuracao.registrosGoogle(ambiente("http://localhost:4200/auth/retorno/google", false));
        assertThat(registros).isNotNull();
        assertThat(registros.findByRegistrationId("google").getRedirectUri())
                .isEqualTo("http://localhost:4200/auth/retorno/google");
        assertThatThrownBy(() -> configuracao.registrosGoogle(
                ambiente("http://outro.example/auth/retorno/google", false)))
                .isInstanceOf(IllegalStateException.class);
    }

    private MockEnvironment ambiente(String retorno, boolean publico) {
        var ambiente = new MockEnvironment().withProperty("CREDPAY_GOOGLE_CLIENT_ID", "cliente-ficticio")
                .withProperty("CREDPAY_GOOGLE_CLIENT_SECRET", "segredo-ficticio-nao-real")
                .withProperty("CREDPAY_GOOGLE_REDIRECT_URI", retorno);
        ambiente.setActiveProfiles(publico ? new String[] {"public-demo", "sessao-demo", "google-login"}
                : new String[] {"sessao-demo", "google-login"});
        return ambiente;
    }
}
