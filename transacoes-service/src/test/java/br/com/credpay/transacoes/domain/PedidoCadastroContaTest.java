package br.com.credpay.transacoes.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PedidoCadastroContaTest {
    private static final String SENHA = "uma frase de acesso longa";

    @Test
    void normalizaEmailSemRemoverPontosOuSufixoEPreservaSenha() {
        var pedido = new PedidoCadastroConta(" Pessoa.Exemplo+CredPay@EXAMPLE.COM ", " " + SENHA + " ");
        assertThat(pedido.email()).isEqualTo("pessoa.exemplo+credpay@example.com");
        assertThat(pedido.senha()).isEqualTo(" " + SENHA + " ");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"sem-arroba", "a@@example.com", "a@localhost", "a..b@example.com", ".a@example.com",
            "a.@example.com", "a b@example.com", "a@-example.com", "a@example-.com", "a@example..com",
            "á@example.com", "a@example.com\nsegredo", "a@example.com:80"})
    void rejeitaEmailInvalido(String email) {
        assertThatIllegalArgumentException().isThrownBy(() -> new PedidoCadastroConta(email, SENHA));
    }

    @Test
    void limitaEmailEParteLocalAntesDePersistir() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PedidoCadastroConta("a".repeat(65) + "@example.com", SENHA));
        assertThatIllegalArgumentException().isThrownBy(() -> new PedidoCadastroConta("a@" + "a".repeat(250) + ".com", SENHA));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"curta", "1234567", "senha-com-controle\u0000fim"})
    void rejeitaSenhaAusenteCurtaOuComControle(String senha) {
        assertThatIllegalArgumentException().isThrownBy(() -> new PedidoCadastroConta("pessoa@example.com", senha));
    }

    @Test
    void aceitaFraseUnicodeSemExigirComposicaoENaoTruncaSenha() {
        var senha = "á".repeat(8);
        assertThat(new PedidoCadastroConta("pessoa@example.com", senha).senha()).isEqualTo(senha);
        assertThat(new PedidoCadastroConta("pessoa@example.com", "🔑".repeat(128)).senha()).isEqualTo("🔑".repeat(128));
        assertThatIllegalArgumentException().isThrownBy(() -> new PedidoCadastroConta("pessoa@example.com", "a".repeat(129)));
        assertThatIllegalArgumentException().isThrownBy(() -> new PedidoCadastroConta("pessoa@example.com", "🔑".repeat(7)));
    }

    @Test
    void representacaoNaoExpoeEmailOuSenha() {
        assertThat(new PedidoCadastroConta("pessoa@example.com", SENHA).toString())
                .doesNotContain("pessoa@example.com", SENHA);
    }
}
