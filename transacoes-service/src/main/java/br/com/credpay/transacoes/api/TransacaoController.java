package br.com.credpay.transacoes.api;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Currency;
import java.util.UUID;

import br.com.credpay.transacoes.application.CriarTransacao;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/transacoes")
public class TransacaoController {

    private final CriarTransacao criarTransacao;

    TransacaoController(CriarTransacao criarTransacao) {
        this.criarTransacao = criarTransacao;
    }

    @PostMapping
    ResponseEntity<TransacaoResponse> criar(@RequestBody TransacaoRequest request) {
        var moeda = Currency.getInstance(request.moeda());
        var resultado = criarTransacao.executar(request.valor(), moeda);
        var location = URI.create("/transacoes/" + resultado.id());
        var response = new TransacaoResponse(
                resultado.id(),
                resultado.valor(),
                resultado.moeda().getCurrencyCode(),
                resultado.status().name());

        return ResponseEntity.created(location).body(response);
    }

    record TransacaoRequest(BigDecimal valor, String moeda) {
    }

    record TransacaoResponse(UUID id, BigDecimal valor, String moeda, String status) {
    }
}
