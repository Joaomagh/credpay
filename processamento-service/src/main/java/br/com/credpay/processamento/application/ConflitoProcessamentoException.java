package br.com.credpay.processamento.application;

public final class ConflitoProcessamentoException extends RuntimeException {

    public ConflitoProcessamentoException(String mensagem) {
        super(mensagem);
    }
}
