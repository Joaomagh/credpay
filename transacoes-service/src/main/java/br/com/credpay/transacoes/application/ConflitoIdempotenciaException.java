package br.com.credpay.transacoes.application;

public final class ConflitoIdempotenciaException extends RuntimeException {

    public ConflitoIdempotenciaException() {
        super("chave de idempotência já utilizada com outro payload");
    }
}
