package br.com.credpay.transacoes.application;

public final class TransicaoRecusadaException extends RuntimeException {

    public TransicaoRecusadaException() {
        super("transição exige transação pendente e causa de criação local");
    }
}
