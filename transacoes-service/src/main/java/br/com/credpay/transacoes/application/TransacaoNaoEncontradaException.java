package br.com.credpay.transacoes.application;

public final class TransacaoNaoEncontradaException extends RuntimeException {

    public TransacaoNaoEncontradaException() {
        super("transação não encontrada");
    }
}
