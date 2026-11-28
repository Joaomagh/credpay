package br.com.credpay.transacoes.api;

import br.com.credpay.transacoes.application.CadastroContaEmail;
import br.com.credpay.transacoes.domain.PedidoCadastroConta;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("sessao-demo")
class RegistroContaController {
    private final CadastroContaEmail cadastro;

    RegistroContaController(CadastroContaEmail cadastro) { this.cadastro = cadastro; }

    @PostMapping(path = "/auth/registro", consumes = "application/json")
    ResponseEntity<Void> registrar(@RequestBody Pedido request, HttpServletRequest http) {
        if (http.getQueryString() != null) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
        cadastro.cadastrar(new PedidoCadastroConta(request.email(), request.senha()));
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).build();
    }

    record Pedido(String email, String senha) {
        @JsonAnySetter
        void rejeitarExtra(String campo, Object valor) {
            throw new IllegalArgumentException("corpo deve conter somente e-mail e senha");
        }
        @Override
        public String toString() { return "PedidoRegistro[redigido]"; }
    }
}
