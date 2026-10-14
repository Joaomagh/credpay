package br.com.credpay.transacoes.application;

public class ConfirmacaoEncerramentoRecusadaException extends RuntimeException {
    public ConfirmacaoEncerramentoRecusadaException(String motivo) { super(motivo); }
}
