package br.com.credpay.transacoes.application;

import java.util.UUID;

import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

public class SolicitarEncerramentoService {

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void executar(UUID pedidoId, UUID transactionId, UUID evidenceRef) {
        // Scaffolding TDD; iniciação controlada somente na fixture.
    }
}
