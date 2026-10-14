package br.com.credpay.transacoes.application;

public interface FalhaConfirmadaRepository {
    void registrarFalhaConfirmada(TransicaoRecebida transicao);
}
