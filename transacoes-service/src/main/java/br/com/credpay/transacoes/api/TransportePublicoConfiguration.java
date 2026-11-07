package br.com.credpay.transacoes.api;

import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@Profile("public-demo")
class TransportePublicoConfiguration {

    @Bean
    static BeanFactoryPostProcessor verificarAntesDoServidor(Environment ambiente) {
        return beanFactory -> validar(ambiente);
    }

    static void validar(Environment ambiente) {
        var obrigatorios = Map.ofEntries(
                Map.entry("server.ssl.enabled", "true"),
                Map.entry("server.ssl.key-store-type", "PKCS12"),
                Map.entry("server.servlet.session.cookie.secure", "true"),
                Map.entry("server.servlet.session.cookie.http-only", "true"),
                Map.entry("server.servlet.session.cookie.same-site", "lax"),
                Map.entry("server.servlet.session.cookie.path", "/"),
                Map.entry("server.servlet.session.cookie.name", "JSESSIONID"),
                Map.entry("server.servlet.session.tracking-modes", "cookie"),
                Map.entry("server.forward-headers-strategy", "none"),
                Map.entry("management.server.address", "127.0.0.1"));
        for (var esperado : obrigatorios.entrySet()) {
            exigirPublico(esperado.getValue().equals(ambiente.getProperty(esperado.getKey())), esperado.getKey());
        }
        exigirPublico(ambiente.getProperty("server.servlet.session.cookie.domain") == null,
                "server.servlet.session.cookie.domain");
        var protocolos = ambiente.getProperty("server.ssl.enabled-protocols", "").split(",", -1);
        exigirPublico(Arrays.stream(protocolos).allMatch(valor -> Set.of("TLSv1.2", "TLSv1.3").contains(valor.strip())),
                "server.ssl.enabled-protocols");
        validarFonteTls(ambiente.getProperty("server.ssl.key-store", ""));
        var senha = ambiente.getProperty("server.ssl.key-store-password");
        exigirPublico(senha != null && senha.length() >= 16 && !senha.isBlank(), "server.ssl.key-store-password");
        int management = portaPublica(ambiente.getProperty("management.server.port", ""), "management.server.port");
        int principal = portaPublica(ambiente.getProperty("server.port", "8080"), "server.port");
        exigirPublico(management == 0 || management != principal, "management.server.port");
        SessaoDemoPublica.validarOrigem(ambiente.getProperty("CREDPAY_ORIGIN_TOKEN"));
    }

    private static void validarFonteTls(String fonte) {
        URI uri;
        try {
            uri = URI.create(fonte);
        } catch (IllegalArgumentException invalida) {
            throw new IllegalStateException("Configuração pública inválida: server.ssl.key-store");
        }
        exigirPublico("file".equals(uri.getScheme()) && uri.getRawAuthority() == null && uri.getRawQuery() == null
                && uri.getRawFragment() == null && uri.getPath() != null && uri.getPath().startsWith("/")
                && !uri.getPath().startsWith("//"), "server.ssl.key-store");
    }

    private static int portaPublica(String valor, String atributo) {
        exigirPublico(valor.matches("[0-9]{1,5}"), atributo);
        int porta = Integer.parseInt(valor);
        exigirPublico(porta <= 65_535, atributo);
        return porta;
    }

    private static void exigirPublico(boolean condicao, String atributo) {
        if (!condicao) { throw new IllegalStateException("Configuração pública inválida: " + atributo); }
    }
}
