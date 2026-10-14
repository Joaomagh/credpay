package br.com.credpay.transacoes.application;

import java.util.Optional;

public record AplicacaoEncerramento(RespostaEncerramentoRecebida resposta, Optional<TransicaoRecebida> historico) { }
