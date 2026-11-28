package br.com.credpay.transacoes.application;

import br.com.credpay.transacoes.domain.PedidoCadastroConta;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@Profile("sessao-demo")
public class CadastroContaEmail {
    private final ContasEmail contas;
    private final PasswordEncoder encoder;

    public CadastroContaEmail(ContasEmail contas, PasswordEncoder encoder) {
        this.contas = contas;
        this.encoder = encoder;
    }

    public void cadastrar(PedidoCadastroConta pedido) {
        if (pedido == null) { throw new IllegalArgumentException("Informe os dados do cadastro."); }
        contas.cadastrar(UUID.randomUUID(), pedido.email(), encoder.encode(pedido.senha()));
    }
}
