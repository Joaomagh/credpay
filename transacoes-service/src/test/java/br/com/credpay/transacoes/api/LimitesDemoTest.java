package br.com.credpay.transacoes.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LimitesDemoTest {

    @ParameterizedTest
    @CsvSource({"POST, /transacoes, CRIACAO, 120", "GET, /transacoes/123, CONSULTA, 300"})
    void deveRecusarIdentidadeAusenteSemAvancarOuConsumirCota(String metodo, String caminho,
                                                            LimitesDemo.Operacao operacao, int cota) throws Exception {
        var relogio = Clock.fixed(Instant.parse("2026-11-04T12:00:43Z"), ZoneOffset.UTC);
        var limites = new LimitesDemo(relogio);
        var requisicao = new MockHttpServletRequest(metodo, caminho);
        var resposta = new MockHttpServletResponse();
        var proximo = new MockFilterChain();

        limites.depoisDaAutorizacao(requisicao, resposta, proximo);

        assertThat(resposta.getStatus()).isEqualTo(401);
        assertThat(proximo.getRequest()).isNull();
        for (int origem = 0; origem < cota; origem++) {
            assertThat(limites.consumir(operacao, "conta-" + origem)).isZero();
        }
        assertThat(limites.consumir(operacao, "conta-extra")).isEqualTo(17);
    }

    @Test
    void deveLimitarOrigensDistintasPeloOrcamentoGlobalECalcularEspera() {
        var relogio = Clock.fixed(Instant.parse("2026-11-04T12:00:43Z"), ZoneOffset.UTC);
        var limites = new LimitesDemo(relogio);
        for (int origem = 0; origem < 60; origem++) {
            assertThat(limites.consumir(LimitesDemo.Operacao.LOGIN, "origem-" + origem)).isZero();
        }
        assertThat(limites.consumir(LimitesDemo.Operacao.LOGIN, "nova-origem")).isEqualTo(17);
        assertThat(limites.consumir(LimitesDemo.Operacao.TOKEN, "nova-origem")).isZero();
    }
}
