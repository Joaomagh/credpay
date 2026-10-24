package br.com.credpay.transacoes.api;

final class IdTransacaoInvalidoException extends RuntimeException {

    IdTransacaoInvalidoException(Throwable causa) {
        super("id deve ser um UUID válido", causa);
    }
}
