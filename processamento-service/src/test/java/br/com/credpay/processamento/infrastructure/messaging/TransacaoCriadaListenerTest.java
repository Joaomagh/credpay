package br.com.credpay.processamento.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import br.com.credpay.processamento.application.ConflitoProcessamentoException;
import br.com.credpay.processamento.application.RegistrarProcessamentoService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.dao.DataAccessResourceFailureException;

class TransacaoCriadaListenerTest {

    @Test
    void receber_deveRejeitarSemRequeueEComDiagnosticoSeguro_quandoIdentidadeConflitar() {
        var registrar = mock(RegistrarProcessamentoService.class);
        doThrow(new ConflitoProcessamentoException("diagnostico-interno-nao-publicavel"))
                .when(registrar).executar(any());
        var listener = new TransacaoCriadaListener(new ObjectMapper(), registrar);

        assertThatThrownBy(() -> listener.receber(mensagemValida()))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessage("TransacaoCriada com conflito de identidade")
                .hasNoCause();
    }

    @Test
    void receber_devePropagarFalhaOperacional_quandoBancoEstiverIndisponivel() {
        var registrar = mock(RegistrarProcessamentoService.class);
        var falha = new DataAccessResourceFailureException("banco indisponivel");
        doThrow(falha).when(registrar).executar(any());
        var listener = new TransacaoCriadaListener(new ObjectMapper(), registrar);

        assertThatThrownBy(() -> listener.receber(mensagemValida())).isSameAs(falha);
    }

    private Message mensagemValida() {
        var eventId = "6f8c829f-dbd0-4a99-bc52-a31b1290be86";
        var transactionId = "38e2905a-9e5e-46d8-835a-33ab85fbdd88";
        var payload = """
                {"eventId":"%s","eventType":"TransacaoCriada","eventVersion":1,
                 "occurredAt":"2026-10-03T12:00:00Z","correlationId":"%s",
                 "data":{"transactionId":"%s","amount":"10.25","currency":"BRL","status":"PENDENTE"}}
                """.formatted(eventId, transactionId, transactionId);
        var propriedades = new MessageProperties();
        propriedades.setMessageId(eventId);
        propriedades.setType("TransacaoCriada");
        propriedades.setCorrelationId(transactionId);
        return new Message(payload.getBytes(StandardCharsets.UTF_8), propriedades);
    }
}
