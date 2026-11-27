package br.com.credpay.transacoes.api;

import java.time.Clock;
import java.util.UUID;

import br.com.credpay.transacoes.application.CadastroContaExterna;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;

@Configuration(proxyBeanMethods = false)
@Profile("sessao-demo")
@EnableWebSecurity
class SessaoDemoConfiguration {

    private static final String LOGIN = "/auth/login";

    @Bean
    @Profile("public-demo")
    SessaoDemoPublica sessaoPublica(@Qualifier("clock") Clock relogio, Environment ambiente) {
        TransportePublicoConfiguration.validar(ambiente);
        return new SessaoDemoPublica(relogio, ambiente.getProperty("CREDPAY_ORIGIN_TOKEN"));
    }

    @Bean
    PasswordEncoder senhaEncoder() {
        return new Pbkdf2PasswordEncoder("", 16, 600_000, SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
    }

    @Bean
    DelegatingFilterProxyRegistrationBean registrarFiltrosDaSessao() {
        var registro = new DelegatingFilterProxyRegistrationBean("springSecurityFilterChain");
        registro.setOrder(-100);
        return registro;
    }

    @Bean
    SecurityFilterChain filtrosDaSessao(HttpSecurity http, JdbcTemplate jdbc, PasswordEncoder encoder,
            @Qualifier("clock") Clock relogio,
            ObjectProvider<SessaoDemoPublica> politicaPublica,
            ObjectProvider<ClientRegistrationRepository> registrosGoogle,
            ObjectProvider<CadastroContaExterna> cadastroExterno) {
        var autenticador = new DaoAuthenticationProvider(usuario -> jdbc.query("""
                SELECT id, usuario, senha_hash FROM contas WHERE usuario = ? AND senha_hash IS NOT NULL
                """, (rs, linha) -> new ContaDemoPrincipal(rs.getObject("id", UUID.class),
                        rs.getString("usuario"), rs.getString("senha_hash")), usuario)
                .stream().findFirst().orElseThrow(() -> new UsernameNotFoundException("Conta inválida")));
        autenticador.setPasswordEncoder(encoder);

        var login = PathPatternRequestMatcher.pathPattern(HttpMethod.POST, LOGIN);
        var limites = new LimitesDemo(relogio);
        var publica = politicaPublica.getIfAvailable();
        http.authenticationProvider(autenticador)
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(acesso -> acesso
                        .requestMatchers(HttpMethod.GET, "/auth/csrf", "/auth/metodos", "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/auth/sessao", "/transacoes/{id}",
                                "/clientes", "/clientes/{id}").authenticated()
                        .requestMatchers(HttpMethod.POST, "/transacoes", "/clientes").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(erros -> erros.authenticationEntryPoint(
                        (req, resp, erro) -> resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED)))
                .formLogin(form -> form.loginPage(LOGIN).loginProcessingUrl(LOGIN)
                        .usernameParameter("usuario").passwordParameter("senha")
                        .successHandler((req, resp, conta) -> {
                            if (publica != null) { publica.registrarLogin(req); }
                            resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
                        })
                        .failureHandler((req, resp, erro) -> resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED)))
                .logout(saida -> {
                    saida.logoutUrl("/auth/logout");
                    if (publica == null) { saida.deleteCookies("JSESSIONID"); }
                    saida.logoutSuccessHandler((req, resp, conta) -> {
                        if (publica != null) { publica.limparCookie(resp); }
                        resp.setStatus(HttpServletResponse.SC_NO_CONTENT);
                    });
                })
                .addFilterBefore((req, resp, chain) -> {
                    if (publica == null || publica.permitir((HttpServletRequest) req, (HttpServletResponse) resp)) {
                        limites.antesDeCsrf(req, resp, chain);
                    }
                }, CsrfFilter.class)
                .addFilterAfter(limites::depoisDaAutorizacao, AuthorizationFilter.class)
                .addFilterBefore((req, resp, chain) -> {
                    var requisicao = (HttpServletRequest) req;
                    if (login.matches(requisicao) && requisicao.getQueryString() != null) {
                        ((HttpServletResponse) resp).setStatus(HttpServletResponse.SC_BAD_REQUEST);
                        return;
                    }
                    chain.doFilter(req, resp);
                }, UsernamePasswordAuthenticationFilter.class);
        var registros = registrosGoogle.getIfAvailable();
        if (registros != null) {
            http.oauth2Login(google -> google.clientRegistrationRepository(registros)
                    .authorizedClientRepository(new HttpSessionOAuth2AuthorizedClientRepository())
                    .loginPage("/entrar")
                    .authorizationEndpoint(inicio -> inicio.baseUri("/auth/externo"))
                    .redirectionEndpoint(retorno -> retorno.baseUri("/auth/retorno/google"))
                    .userInfoEndpoint(info -> info.oidcUserService(
                            new ContaGoogleOidcUserService(cadastroExterno.getObject())))
                    .successHandler((req, resp, autenticacao) -> {
                        if (publica != null) { publica.registrarLogin(req); }
                        resp.sendRedirect("/entrar");
                    })
                    .failureHandler((req, resp, erro) -> resp.sendRedirect("/entrar?falha=google")));
        }
        return http.build();
    }
}
