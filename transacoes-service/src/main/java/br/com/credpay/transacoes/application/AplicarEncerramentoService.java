package br.com.credpay.transacoes.application;

import java.util.Optional;

import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

public class AplicarEncerramentoService {

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AplicacaoEncerramento executar(RespostaEncerramentoRecebida resposta) {
        return new AplicacaoEncerramento(resposta, Optional.empty());
    }
}
