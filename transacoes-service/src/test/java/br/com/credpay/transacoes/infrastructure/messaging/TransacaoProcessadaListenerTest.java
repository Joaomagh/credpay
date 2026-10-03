package br.com.credpay.transacoes.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import br.com.credpay.transacoes.application.AplicarResultadoService;
import br.com.credpay.transacoes.application.ConflitoResultadoException;
import br.com.credpay.transacoes.application.TransicaoRecusadaException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.dao.DataAccessResourceFailureException;

class TransacaoProcessadaListenerTest {

    private final AplicarResultadoService aplicar = mock(AplicarResultadoService.class);
    private final TransacaoProcessadaListener listener = new TransacaoProcessadaListener(new ObjectMapper(), aplicar);

    @Test
    void receber_deveRejeitarContratoInvalidoSemRequeueOuAcessoAoCasoDeUso() {
        assertThatThrownBy(() -> listener.receber(new Message("{".getBytes(StandardCharsets.UTF_8), new MessageProperties())))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessage("TransacaoProcessada inválida: JSON inválido").hasNoCause();
        verifyNoInteractions(aplicar);
    }

    @Test
    void receber_deveRejeitarConflitoSemRequeueOuCausaInterna() {
        doThrow(new ConflitoResultadoException()).when(aplicar).executar(any());

        assertThatThrownBy(() -> listener.receber(mensagemValida()))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessage("TransacaoProcessada com conflito de identidade").hasNoCause();
    }

    @Test
    void receber_deveRejeitarRecusaSemRequeueOuCausaInterna() {
        doThrow(new TransicaoRecusadaException()).when(aplicar).executar(any());

        assertThatThrownBy(() -> listener.receber(mensagemValida()))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessage("TransacaoProcessada recusada por transação ou causa inválida").hasNoCause();
    }

    @Test
    void receber_devePropagarFalhaOperacionalSemTransformarEmConflito() {
        var falha = new DataAccessResourceFailureException("falha operacional controlada");
        doThrow(falha).when(aplicar).executar(any());

        assertThatThrownBy(() -> listener.receber(mensagemValida())).isSameAs(falha);
    }

    private Message mensagemValida() {
        var evento = UUID.randomUUID();
        var id = UUID.randomUUID();
        var body = """
                {"eventId":"%s","eventType":"TransacaoProcessada","eventVersion":1,
                 "occurredAt":"2026-10-03T12:00:00Z","correlationId":"%s","causationId":"%s",
                 "data":{"transactionId":"%s","status":"APROVADA"}}
                """.formatted(evento, id, UUID.randomUUID(), id);
        var properties = new MessageProperties();
        properties.setMessageId(evento.toString());
        properties.setType("TransacaoProcessada");
        properties.setCorrelationId(id.toString());
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }
}
