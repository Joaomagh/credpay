package br.com.credpay.transacoes.api;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.DelegatingFilterProxyRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

@Configuration(proxyBeanMethods = false)
@Profile("sessao-demo")
@EnableWebSecurity
class SessaoDemoConfiguration {

    private static final String LOGIN = "/auth/login";

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
    SecurityFilterChain filtrosDaSessao(HttpSecurity http, JdbcTemplate jdbc, PasswordEncoder encoder) {
        var autenticador = new DaoAuthenticationProvider(usuario -> jdbc.query("""
                SELECT id, usuario, senha_hash FROM contas WHERE usuario = ?
                """, (rs, linha) -> new ContaDemoPrincipal(rs.getObject("id", UUID.class),
                        rs.getString("usuario"), rs.getString("senha_hash")), usuario)
                .stream().findFirst().orElseThrow(() -> new UsernameNotFoundException("Conta inválida")));
        autenticador.setPasswordEncoder(encoder);

        var login = PathPatternRequestMatcher.pathPattern(HttpMethod.POST, LOGIN);
        http.authenticationManager(new ProviderManager(autenticador))
                .requestCache(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(acesso -> acesso
                        .requestMatchers(HttpMethod.GET, "/auth/csrf", "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.GET, "/auth/sessao", "/transacoes/{id}").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(erros -> erros.authenticationEntryPoint(
                        (req, resp, erro) -> resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED)))
                .formLogin(form -> form.loginPage(LOGIN).loginProcessingUrl(LOGIN)
                        .usernameParameter("usuario").passwordParameter("senha")
                        .successHandler((req, resp, conta) -> resp.setStatus(HttpServletResponse.SC_NO_CONTENT))
                        .failureHandler((req, resp, erro) -> resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED)))
                .logout(saida -> saida.logoutUrl("/auth/logout").deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((req, resp, conta) -> resp.setStatus(HttpServletResponse.SC_NO_CONTENT)))
                .addFilterBefore((req, resp, chain) -> {
                    var requisicao = (HttpServletRequest) req;
                    if (login.matches(requisicao) && requisicao.getQueryString() != null) {
                        ((HttpServletResponse) resp).setStatus(HttpServletResponse.SC_BAD_REQUEST);
                        return;
                    }
                    chain.doFilter(req, resp);
                }, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
