package br.com.credpay.transacoes.api;

final class IdempotencyKeyInvalidaException extends RuntimeException {

    IdempotencyKeyInvalidaException(String mensagem) {
        super(mensagem);
    }

    IdempotencyKeyInvalidaException(String mensagem, Throwable causa) {
        super(mensagem, causa);
    }
}
