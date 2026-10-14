package br.com.credpay.transacoes.application;

public class PedidoEncerramentoRecusadoException extends RuntimeException {
    public PedidoEncerramentoRecusadoException(String motivo) { super(motivo); }
}
