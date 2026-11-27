package br.com.credpay.transacoes.api;

import java.net.URI;
import java.security.Principal;
import java.util.UUID;

import br.com.credpay.transacoes.application.Clientes;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("sessao-demo")
@RequestMapping("/clientes")
public class ClienteController {

    private final Clientes clientes;

    ClienteController(Clientes clientes) {
        this.clientes = clientes;
    }

    @PostMapping
    ResponseEntity<Clientes.Resultado> cadastrar(@RequestBody ClienteRequest request,
            @RequestHeader(name = "X-CredPay-Conta", required = false) UUID contaEsperada, Principal principal) {
        var cliente = clientes.cadastrar(conta(principal, contaEsperada), request.nome());
        return ResponseEntity.created(URI.create("/clientes/" + cliente.id()))
                .cacheControl(CacheControl.noStore()).body(cliente);
    }

    private UUID conta(Principal principal, UUID esperada) {
        var id = ContaDemoPrincipal.idDaConta(principal);
        if (id == null || (esperada != null && !esperada.equals(id))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return id;
    }

    @GetMapping("/{id}")
    ResponseEntity<Clientes.Resultado> buscar(@PathVariable String id,
            @RequestHeader(name = "X-CredPay-Conta", required = false) UUID contaEsperada, Principal principal) {
        var contaId = conta(principal, contaEsperada);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(clientes.buscar(contaId, uuid(id, "id")));
    }

    @GetMapping
    ResponseEntity<Clientes.Pagina> listar(@RequestParam MultiValueMap<String, String> parametros,
            @RequestHeader(name = "X-CredPay-Conta", required = false) UUID contaEsperada, Principal principal) {
        var contaId = conta(principal, contaEsperada);
        if (parametros.entrySet().stream().anyMatch(entrada ->
                (!entrada.getKey().equals("limite") && !entrada.getKey().equals("apos"))
                        || entrada.getValue().size() != 1)) {
            throw new RequisicaoClienteInvalidaException("parâmetros permitidos: limite e apos, sem repetição");
        }
        int limite = 20;
        if (parametros.containsKey("limite")) {
            try {
                limite = Integer.parseInt(parametros.getFirst("limite"));
            } catch (NumberFormatException exception) {
                throw new RequisicaoClienteInvalidaException("limite deve ser inteiro entre 1 e 50");
            }
        }
        if (limite < 1 || limite > 50) {
            throw new RequisicaoClienteInvalidaException("limite deve ser inteiro entre 1 e 50");
        }
        var apos = parametros.containsKey("apos") ? uuid(parametros.getFirst("apos"), "apos") : null;
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(clientes.listar(contaId, limite, apos));
    }

    private UUID uuid(String valor, String campo) {
        try {
            var id = UUID.fromString(valor);
            if (!id.toString().equalsIgnoreCase(valor)) {
                throw new IllegalArgumentException();
            }
            return id;
        } catch (IllegalArgumentException exception) {
            throw new RequisicaoClienteInvalidaException(campo + " deve ser um UUID válido");
        }
    }

    record ClienteRequest(String nome) {

        @JsonAnySetter
        void rejeitarCampoExtra(String campo, Object valor) {
            throw new IllegalArgumentException("corpo deve conter somente nome");
        }
    }
}
