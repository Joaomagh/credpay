package br.com.credpay.transacoes.domain;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class IdentidadeExternaTest {
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void deveExigirEmissor(String emissor) {
        assertThatThrownBy(() -> new IdentidadeExterna(emissor, "sujeito"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Identidade externa inválida");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    void deveExigirSujeito(String sujeito) {
        assertThatThrownBy(() -> new IdentidadeExterna("https://accounts.google.com", sujeito))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Identidade externa inválida");
    }
}
