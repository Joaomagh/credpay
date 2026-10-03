package br.com.credpay.transacoes.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import br.com.credpay.transacoes.domain.StatusTransacao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TransacaoProcessadaRecebidaTest {

    @ParameterizedTest
    @ValueSource(strings = {"evento", "transacao", "instante", "correlacao", "causa", "status"})
    void construir_deveRecusarCampoNulo(String campo) {
        var id = UUID.randomUUID();

        assertThatThrownBy(() -> new TransacaoProcessadaRecebida(
                campo.equals("evento") ? null : UUID.randomUUID(),
                campo.equals("transacao") ? null : id,
                campo.equals("instante") ? null : Instant.EPOCH,
                campo.equals("correlacao") ? null : id,
                campo.equals("causa") ? null : UUID.randomUUID(),
                campo.equals("status") ? null : StatusTransacao.APROVADA))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void construir_deveRecusarEstadoNaoFinal() {
        var id = UUID.randomUUID();

        assertThatThrownBy(() -> new TransacaoProcessadaRecebida(
                UUID.randomUUID(), id, Instant.EPOCH, id, UUID.randomUUID(), StatusTransacao.PENDENTE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("resultado deve ser APROVADA ou REJEITADA");
    }

    @Test
    void construir_deveRecusarCorrelacaoDiferenteDaTransacao() {
        assertThatThrownBy(() -> new TransacaoProcessadaRecebida(UUID.randomUUID(), UUID.randomUUID(),
                Instant.EPOCH, UUID.randomUUID(), UUID.randomUUID(), StatusTransacao.APROVADA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("correlationId deve corresponder a transactionId");
    }
}
