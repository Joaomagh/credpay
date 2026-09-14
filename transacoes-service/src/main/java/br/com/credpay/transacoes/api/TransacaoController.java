package br.com.credpay.transacoes.api;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Currency;
import java.util.UUID;

import br.com.credpay.transacoes.application.BuscarTransacao;
import br.com.credpay.transacoes.application.CriarTransacao;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/transacoes")
public class TransacaoController {

    private final CriarTransacao criarTransacao;
    private final BuscarTransacao buscarTransacao;

    TransacaoController(CriarTransacao criarTransacao, BuscarTransacao buscarTransacao) {
        this.criarTransacao = criarTransacao;
        this.buscarTransacao = buscarTransacao;
    }

    @PostMapping
    ResponseEntity<TransacaoResponse> criar(@RequestBody TransacaoRequest request) {
        var moeda = converterMoeda(request.moeda());
        var resultado = criarTransacao.executar(request.valor(), moeda);
        var location = URI.create("/transacoes/" + resultado.id());
        var response = new TransacaoResponse(
                resultado.id(),
                resultado.valor(),
                resultado.moeda().getCurrencyCode(),
                resultado.status().name());

        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{id}")
    ResponseEntity<TransacaoResponse> buscar(@PathVariable UUID id) {
        var resultado = buscarTransacao.executar(id);
        var response = new TransacaoResponse(
                resultado.id(),
                resultado.valor(),
                resultado.moeda().getCurrencyCode(),
                resultado.status().name());

        return ResponseEntity.ok(response);
    }

    private Currency converterMoeda(String codigo) {
        if (codigo == null) {
            return null;
        }
        try {
            return Currency.getInstance(codigo);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "moeda deve ser um código ISO 4217 válido em letras maiúsculas", exception);
        }
    }

    record TransacaoRequest(BigDecimal valor, String moeda) {
    }

    record TransacaoResponse(UUID id, BigDecimal valor, String moeda, String status) {
    }
}
