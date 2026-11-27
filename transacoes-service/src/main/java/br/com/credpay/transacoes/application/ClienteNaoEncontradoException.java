package br.com.credpay.transacoes.application;

public final class ClienteNaoEncontradoException extends RuntimeException {

    public ClienteNaoEncontradoException() {
        super("cliente não encontrado");
    }
}
