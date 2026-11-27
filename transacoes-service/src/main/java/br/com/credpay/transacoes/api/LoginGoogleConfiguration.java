package br.com.credpay.transacoes.api;

import java.net.URI;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

@Configuration(proxyBeanMethods = false)
@Profile("google-login")
class LoginGoogleConfiguration {
    @Bean
    ClientRegistrationRepository registrosGoogle(Environment ambiente) {
        var id = ambiente.getProperty("CREDPAY_GOOGLE_CLIENT_ID");
        var segredo = ambiente.getProperty("CREDPAY_GOOGLE_CLIENT_SECRET");
        var retorno = ambiente.getProperty("CREDPAY_GOOGLE_REDIRECT_URI");
        if (!ambiente.acceptsProfiles(Profiles.of("sessao-demo"))
                || !presente(id) || !presente(segredo) || !presente(retorno)) {
            throw invalida();
        }
        try {
            var uri = URI.create(retorno);
            var local = "localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost())
                    || "[::1]".equals(uri.getHost());
            var https = "https".equals(uri.getScheme()) && (uri.getPort() == -1 || uri.getPort() == 443);
            var httpLocal = !ambiente.acceptsProfiles(Profiles.of("public-demo")) && local
                    && "http".equals(uri.getScheme()) && uri.getPort() != 0;
            if ((!https && !httpLocal) || uri.getHost() == null || uri.getRawUserInfo() != null
                    || !"/auth/retorno/google".equals(uri.getRawPath())
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw invalida();
            }
            var registro = CommonOAuth2Provider.GOOGLE.getBuilder("google").clientId(id).clientSecret(segredo)
                    .scope("openid").redirectUri(retorno)
                    .clientSettings(ClientRegistration.ClientSettings.builder().requireProofKey(true).build()).build();
            return new InMemoryClientRegistrationRepository(registro);
        } catch (IllegalArgumentException erro) {
            throw invalida();
        }
    }

    private static boolean presente(String valor) {
        return valor != null && !valor.isBlank() && valor.length() <= 2048
                && valor.codePoints().noneMatch(Character::isISOControl);
    }

    private static IllegalStateException invalida() {
        return new IllegalStateException("Configuração Google inválida");
    }
}
