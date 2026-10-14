package br.com.credpay.processamento.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.credpay.processamento.domain.StatusProcessamento;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class ArbitrarEncerramentoTest {
    private final UUID transactionId = UUID.fromString("a76a5537-a838-4ae5-a52d-26cd8463b7f0");
    private final UUID creationEventId = UUID.fromString("61e71d1c-2844-459d-8490-c77765b23690");
    private final UUID outputEventId = UUID.fromString("ed826c39-d7cc-4fe2-a225-b106e8b73367");

    @ParameterizedTest
    @EnumSource(StatusProcessamento.class)
    void executar_devePreservarSaidaFinanceira_quandoDecisaoJaExiste(StatusProcessamento status) {
        var original = decisao(status);

        var resposta = ArbitrarEncerramento.executar(transactionId, creationEventId, Optional.of(original));

        assertThat(resposta.status()).isEqualTo(ArbitrarEncerramento.Status.DECISAO_EXISTENTE);
        assertThat(resposta.outputEventId()).contains(outputEventId);
        assertThat(original.status()).isEqualTo(status);
        assertThat(original.valor()).isEqualTo(new BigDecimal("50.000"));
    }

    @ParameterizedTest
    @EnumSource(Identidade.class)
    void executar_deveRejeitarIdentidadeDivergenteSemExporSaida(Identidade identidade) {
        var original = decisao(StatusProcessamento.APROVADA);
        var outro = UUID.fromString("342a0f15-f8a9-4811-a59e-32f26673ebaf");
        var transacao = identidade == Identidade.TRANSACAO ? outro : transactionId;
        var criacao = identidade == Identidade.CRIACAO ? outro : creationEventId;

        assertThatThrownBy(() -> ArbitrarEncerramento.executar(transacao, criacao, Optional.of(original)))
                .isInstanceOf(ConflitoProcessamentoException.class)
                .hasMessage("identidade do encerramento divergente");
        assertThat(original.outputEventId()).isEqualTo(outputEventId);
        assertThat(original.status()).isEqualTo(StatusProcessamento.APROVADA);
    }

    @Test
    void executar_deveExigirBloqueioDuravel_quandoDecisaoAusente() {
        var resposta = ArbitrarEncerramento.executar(transactionId, creationEventId, Optional.empty());

        assertThat(resposta.status()).isEqualTo(ArbitrarEncerramento.Status.BLOQUEIO_NECESSARIO);
        assertThat(resposta.outputEventId()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"transactionId", "creationEventId", "decisaoExistente"})
    void executar_deveRejeitarParametroAusenteAntesDaArbitragem(String parametro) {
        UUID transacao = "transactionId".equals(parametro) ? null : transactionId;
        UUID criacao = "creationEventId".equals(parametro) ? null : creationEventId;
        Optional<ProcessamentoRegistrado> decisao = "decisaoExistente".equals(parametro) ? null : Optional.empty();

        assertThatThrownBy(() -> ArbitrarEncerramento.executar(transacao, criacao, decisao))
                .isInstanceOf(NullPointerException.class).hasMessage(parametro);
    }

    private ProcessamentoRegistrado decisao(StatusProcessamento status) {
        return new ProcessamentoRegistrado(creationEventId, transactionId,
                "TransacaoCriada", 1, Instant.parse("2026-10-14T12:00:00.123456789Z"),
                transactionId, "PENDENTE", new BigDecimal("50.000"), Currency.getInstance("BRL"),
                new BigDecimal("100.00"), status, Instant.parse("2026-10-14T12:00:01.987654Z"), outputEventId);

    }

    enum Identidade { TRANSACAO, CRIACAO }
}
