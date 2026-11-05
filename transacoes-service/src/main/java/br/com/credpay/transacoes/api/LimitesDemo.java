package br.com.credpay.transacoes.api;

import java.io.IOException;
import java.time.Clock;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

final class LimitesDemo {

    private static final int SEGUNDOS_POR_JANELA = 60;

    private static final PathPatternRequestMatcher TOKEN =
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/auth/csrf");
    private static final PathPatternRequestMatcher LOGIN =
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/auth/login");
    private static final PathPatternRequestMatcher CRIACAO =
            PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/transacoes");
    private static final PathPatternRequestMatcher CONSULTA =
            PathPatternRequestMatcher.pathPattern(HttpMethod.GET, "/transacoes/{id}");

    private final Clock relogio;
    private final Map<Operacao, Janela> janelas = new EnumMap<>(Operacao.class);

    LimitesDemo(Clock relogio) {
        this.relogio = relogio;
    }

    void antesDeCsrf(ServletRequest requisicao, ServletResponse resposta, FilterChain proximo)
            throws IOException, ServletException {
        var http = (HttpServletRequest) requisicao;
        if (TOKEN.matches(http)) {
            aplicar(Operacao.TOKEN, http.getRemoteAddr(), http, (HttpServletResponse) resposta, proximo);
        } else if (LOGIN.matches(http)) {
            aplicar(Operacao.LOGIN, http.getRemoteAddr(), http, (HttpServletResponse) resposta, proximo);
        } else {
            proximo.doFilter(requisicao, resposta);
        }
    }

    void depoisDaAutorizacao(ServletRequest requisicao, ServletResponse resposta, FilterChain proximo)
            throws IOException, ServletException {
        var http = (HttpServletRequest) requisicao;
        Operacao operacao;
        if (CRIACAO.matches(http)) {
            operacao = Operacao.CRIACAO;
        } else if (CONSULTA.matches(http)) {
            operacao = Operacao.CONSULTA;
        } else {
            proximo.doFilter(requisicao, resposta);
            return;
        }
        var conta = ContaDemoPrincipal.idDaConta(http.getUserPrincipal());
        if (conta == null) {
            ((HttpServletResponse) resposta).setStatus(HttpStatus.UNAUTHORIZED.value());
            return;
        }
        aplicar(operacao, conta.toString(), http, (HttpServletResponse) resposta, proximo);
    }

    private void aplicar(Operacao operacao, String origem, HttpServletRequest requisicao, HttpServletResponse resposta,
                         FilterChain proximo) throws IOException, ServletException {
        var espera = consumir(operacao, origem);
        if (espera == 0) {
            proximo.doFilter(requisicao, resposta);
            return;
        }
        resposta.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        resposta.setIntHeader(HttpHeaders.RETRY_AFTER, espera);
    }

    synchronized int consumir(Operacao operacao, String origem) {
        long agora = relogio.instant().getEpochSecond();
        long minuto = Math.floorDiv(agora, SEGUNDOS_POR_JANELA);
        var janela = janelas.get(operacao);
        if (janela == null || janela.minuto != minuto) {
            janela = new Janela(minuto);
            janelas.put(operacao, janela);
        }
        int consumido = janela.origens.getOrDefault(origem, 0);
        if (janela.total >= operacao.global || consumido >= operacao.porOrigem) {
            return SEGUNDOS_POR_JANELA - Math.floorMod(agora, SEGUNDOS_POR_JANELA);
        }
        janela.origens.put(origem, consumido + 1);
        janela.total++;
        return 0;
    }

    enum Operacao {
        TOKEN(30, 120), LOGIN(20, 60), CRIACAO(12, 120), CONSULTA(45, 300);

        private final int porOrigem;
        private final int global;

        Operacao(int porOrigem, int global) {
            this.porOrigem = porOrigem;
            this.global = global;
        }
    }

    private static final class Janela {
        private final long minuto;
        private final Map<String, Integer> origens = new HashMap<>();
        private int total;

        private Janela(long minuto) {
            this.minuto = minuto;
        }
    }
}
