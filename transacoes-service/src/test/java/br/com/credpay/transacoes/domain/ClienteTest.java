package br.com.credpay.transacoes.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class ClienteTest {

    private static final UUID ID = UUID.fromString("909f437c-3e6f-4ceb-803a-fd894c65cb36");
    private static final UUID CONTA = UUID.fromString("ab24a164-5469-48b9-9158-2b01335c329e");

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\u00a0\u2003\u202f", "Cliente\nExemplo", "Cliente\u007f", "Cliente\u0085",
            "\ud800", "\udc00"})
    void criar_deveRejeitarNomeInvalido_semRefletirSeuConteudo(String nome) {
        assertThatThrownBy(() -> new Cliente(ID, CONTA, nome))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("nome deve conter entre 1 e 120 caracteres, sem controles");
    }

    @Test
    void criar_deveNormalizarEspacosExternos_preservandoAcentosEPontuacao() {
        var cliente = new Cliente(ID, CONTA, "\u00a0 João d’Ávila-Silva \u2003");

        assertThat(cliente.nome()).isEqualTo("João d’Ávila-Silva");
    }

    @Test
    void criar_deveContarPontosDeCodigo_emVezDeUnidadesUtf16() {
        var caractere = "\ud801\udc00";
        var cliente = new Cliente(ID, CONTA, caractere.repeat(120));

        assertThat(cliente.nome()).isEqualTo(caractere.repeat(120));
        assertThatThrownBy(() -> new Cliente(ID, CONTA, caractere.repeat(121)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("nome deve conter entre 1 e 120 caracteres, sem controles");
    }

    @Test
    void criar_deveExigirContaProprietaria() {
        assertThatThrownBy(() -> new Cliente(ID, null, "Cliente Exemplo"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("conta deve ser informada");
    }

    @Test
    void criar_deveExigirIdentidadeDoCliente() {
        assertThatThrownBy(() -> new Cliente(null, CONTA, "Cliente Exemplo"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("id deve ser informado");
    }
}
