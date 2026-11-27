package br.com.credpay.transacoes.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PedidoCobrancaTest {
    private static final UUID CLIENTE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final LocalDate DATA = LocalDate.of(2026, 11, 27);

    @Test
    void criar_deveNormalizarDescricaoEValor_semArredondarOuAlterarData() {
        var pedido = new PedidoCobranca(CLIENTE, "\u00a0 Acompanhamento mensal \u2003",
                new BigDecimal("250.000"), "BRL", DATA);
        assertThat(pedido.descricao()).isEqualTo("Acompanhamento mensal");
        assertThat(pedido.valor()).isEqualTo(new BigDecimal("250.00"));
        assertThat(pedido.vencimento()).isEqualTo(DATA);
        assertThat(pedido.clienteId()).isEqualTo(CLIENTE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "0.001", "1.001", "100000000000000000.00",
            "1e+2147483647", "1e-2147483647"})
    void criar_deveRecusarValorInvalido_semArredondamento(String valor) {
        assertThatThrownBy(() -> new PedidoCobranca(CLIENTE, "Serviço", new BigDecimal(valor), "BRL", DATA))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("valor BRL inválido");
    }

    @Test
    void criar_deveAceitarLimitesMonetariosEEquivalenciaDecimal() {
        for (var valor : new String[] {"0.01", "99999999999999999.99", "1e2", "10.00000"}) {
            var pedido = new PedidoCobranca(CLIENTE, "Serviço", new BigDecimal(valor), "BRL", DATA);
            assertThat(pedido.valor()).isEqualByComparingTo(valor);
            assertThat(pedido.valor().scale()).isEqualTo(2);
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\u00a0\u2003", "Serviço\nmensal", "Serviço\u007f", "\ud800"})
    void criar_deveRecusarDescricaoInvalida_semRefletirConteudo(String descricao) {
        assertThatThrownBy(() -> new PedidoCobranca(CLIENTE, descricao, BigDecimal.ONE, "BRL", DATA))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("descrição deve conter de 1 a 240 caracteres, sem controles");
    }

    @Test
    void criar_deveContarDescricaoPorPontosDeCodigo() {
        var caractere = "\ud801\udc00";
        assertThat(new PedidoCobranca(CLIENTE, caractere.repeat(240), BigDecimal.ONE, "BRL", DATA).descricao())
                .isEqualTo(caractere.repeat(240));
        assertThatThrownBy(() -> new PedidoCobranca(CLIENTE, caractere.repeat(241), BigDecimal.ONE, "BRL", DATA))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"USD", "EUR", "brl"})
    void criar_deveExigirMoedaBrl(String moeda) {
        assertThatThrownBy(() -> new PedidoCobranca(CLIENTE, "Serviço", BigDecimal.ONE, moeda, DATA))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("moeda deve ser BRL");
    }

    @Test
    void criar_deveExigirClienteValorEVencimentoDeCalendario() {
        assertThatThrownBy(() -> new PedidoCobranca(null, "Serviço", BigDecimal.ONE, "BRL", DATA))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("cliente deve ser informado");
        assertThatThrownBy(() -> new PedidoCobranca(CLIENTE, "Serviço", null, "BRL", DATA))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("valor BRL inválido");
        for (var data : new LocalDate[] {null, LocalDate.MIN, LocalDate.MAX}) {
            assertThatThrownBy(() -> new PedidoCobranca(CLIENTE, "Serviço", BigDecimal.ONE, "BRL", data))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("vencimento deve ser uma data de 0001 a 9999");
        }
    }
}
