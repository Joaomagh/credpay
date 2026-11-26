package br.com.credpay.transacoes.api;

final class RequisicaoClienteInvalidaException extends RuntimeException {

    RequisicaoClienteInvalidaException(String mensagem) {
        super(mensagem);
    }
}
