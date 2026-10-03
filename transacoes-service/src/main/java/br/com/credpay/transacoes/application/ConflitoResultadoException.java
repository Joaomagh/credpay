package br.com.credpay.transacoes.application;

public final class ConflitoResultadoException extends RuntimeException {

    public ConflitoResultadoException() {
        super("resultado recebido conflita com evento ou transação já concluída");
    }
}
