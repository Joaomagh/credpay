package br.com.credpay.processamento.application;

import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

public class RegistrarEncerramentoService {

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void executar(PedidoEncerramentoRecebido pedido) {
        // Scaffolding TDD; sem descoberta como bean em produção.
    }
}
