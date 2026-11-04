package br.com.credpay.transacoes.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class LimitesDemoTest {

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
