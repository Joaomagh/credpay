package br.com.credpay.transacoes.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.UUID;

import br.com.credpay.transacoes.domain.PedidoCadastroConta;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;

class CadastroContaEmailTest {
    private final Pbkdf2PasswordEncoder encoder = Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();

    @Test
    void criaIdentidadesDistintasComEmailNormalizadoESomenteHashComSal() {
        var contas = new ContasFixture();
        var cadastro = new CadastroContaEmail(contas, encoder);
        var pedido = new PedidoCadastroConta(" Pessoa@EXAMPLE.COM ", "uma frase longa de acesso");
        cadastro.cadastrar(pedido);
        var primeiroId = contas.id;
        var primeiroHash = contas.hash;
        assertThat(contas.email).isEqualTo("pessoa@example.com");
        assertThat(primeiroId).isNotNull();
        assertThat(primeiroHash).isNotEqualTo(pedido.senha());
        assertThat(encoder.matches(pedido.senha(), primeiroHash)).isTrue();
        cadastro.cadastrar(new PedidoCadastroConta("outra@example.com", pedido.senha()));
        assertThat(contas.id).isNotEqualTo(primeiroId);
        assertThat(contas.hash).isNotEqualTo(primeiroHash);
    }

    @Test
    void falhaDePersistenciaNaoViraCadastroConfirmado() {
        var cadastro = new CadastroContaEmail((id, email, hash) -> { throw new IllegalStateException("Banco indisponível"); }, encoder);
        assertThatIllegalStateException().isThrownBy(() -> cadastro.cadastrar(
                new PedidoCadastroConta("pessoa@example.com", "uma frase longa de acesso")));
    }

    @Test
    void exigePedidoAntesDeCriarConta() {
        var contas = new ContasFixture();
        assertThatIllegalArgumentException().isThrownBy(() -> new CadastroContaEmail(contas, encoder).cadastrar(null));
        assertThat(contas.id).isNull();
    }

    private static final class ContasFixture implements ContasEmail {
        private UUID id;
        private String email;
        private String hash;
        @Override
        public void cadastrar(UUID conta, String endereco, String senhaHash) {
            this.id = conta;
            this.email = endereco;
            this.hash = senhaHash;
        }
    }
}
