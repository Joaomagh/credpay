package br.com.credpay.transacoes.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import br.com.credpay.transacoes.application.TransacaoProcessadaRecebida;
import br.com.credpay.transacoes.domain.StatusTransacao;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class TransacaoProcessadaMessageParserTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID TRANSACTION_ID = UUID.randomUUID();
    private static final UUID CAUSE_ID = UUID.randomUUID();
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-03T12:00:00.123456789Z");
    private final TransacaoProcessadaMessageParser parser = new TransacaoProcessadaMessageParser(new ObjectMapper());

    @ParameterizedTest
    @EnumSource(value = StatusTransacao.class, names = {"APROVADA", "REJEITADA"})
    void parsear_deveMapearTodosOsCamposEPreservarNanos(StatusTransacao status) {
        assertThat(parser.parsear(mensagem(payload(status))))
                .isEqualTo(new TransacaoProcessadaRecebida(EVENT_ID, TRANSACTION_ID, OCCURRED_AT,
                        TRANSACTION_ID, CAUSE_ID, status));
    }

    @ParameterizedTest
    @MethodSource("camposInvalidos")
    void parsear_deveRecusarCampoInvalidoSemExporConteudo(String campo, String valor) throws Exception {
        var root = (ObjectNode) new ObjectMapper().readTree(payload(StatusTransacao.APROVADA));
        var objeto = campo.equals("transactionId") || campo.equals("status") ? (ObjectNode) root.get("data") : root;
        if (valor.equals("ausente")) objeto.remove(campo);
        else objeto.set(campo, new ObjectMapper().readTree(valor));

        assertThatThrownBy(() -> parser.parsear(mensagem(root.toString())))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(campo + " inválido").hasNoCause();
    }

    static Stream<Arguments> camposInvalidos() {
        var invalidos = Stream.of("eventType", "eventVersion", "eventId", "correlationId", "causationId",
                        "occurredAt", "data", "transactionId", "status")
                .flatMap(campo -> Stream.of("ausente", "null", "true", "12", "{}", "[]")
                        .filter(valor -> !campo.equals("data") || !valor.equals("{}"))
                        .map(valor -> Arguments.of(campo, valor)));
        return Stream.concat(invalidos, Stream.of(
                Arguments.of("eventType", "\"Outro\""),
                Arguments.of("eventVersion", "2"), Arguments.of("eventVersion", "1.0"),
                Arguments.of("eventVersion", "\"1\""),
                Arguments.of("eventId", "\"1-1-1-1-1\""),
                Arguments.of("transactionId", "\"conteudo-nao-deve-aparecer\""),
                Arguments.of("correlationId", "\"" + UUID.randomUUID() + "\""),
                Arguments.of("causationId", "\"causa-invalida\""),
                Arguments.of("occurredAt", "\"instante-invalido\""),
                Arguments.of("status", "\"PENDENTE\""), Arguments.of("status", "\"FALHOU\"")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "12", "true", "\"texto\"", "{\"payload-privado\":"})
    void parsear_deveRecusarJsonOuEnvelopeInvalidoSemExporConteudo(String json) {
        assertThatThrownBy(() -> parser.parsear(mensagem(json)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(json.startsWith("{") ? "JSON inválido" : "envelope inválido").hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {" {}", " conteudo-privado"})
    void parsear_deveRecusarConteudoDepoisDoEnvelope(String sufixo) {
        assertThatThrownBy(() -> parser.parsear(mensagem(payload(StatusTransacao.APROVADA) + sufixo)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("JSON inválido").hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"messageId", "type", "correlationId"})
    void parsear_deveRecusarPropriedadeAmqpDivergenteOuAusente(String campo) {
        for (String valor : new String[] {null, "conteudo-nao-deve-aparecer"}) {
            var message = mensagem(payload(StatusTransacao.APROVADA));
            switch (campo) {
                case "messageId" -> message.getMessageProperties().setMessageId(valor);
                case "type" -> message.getMessageProperties().setType(valor);
                case "correlationId" -> message.getMessageProperties().setCorrelationId(valor);
                default -> throw new AssertionError("campo inválido na fixture");
            }
            assertThatThrownBy(() -> parser.parsear(message)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("propriedades AMQP inconsistentes").hasNoCause();
        }
    }

    @Test
    void parsear_deveIgnorarCamposExtrasEPreservarInstanteEquivalente() throws Exception {
        var root = (ObjectNode) new ObjectMapper().readTree(payload(StatusTransacao.APROVADA));
        root.put("occurredAt", "2026-10-03T09:00:00.123456789-03:00");
        root.put("extensao", "compatível");
        ((ObjectNode) root.get("data")).put("extensao", 42);

        assertThat(parser.parsear(mensagem(root.toString())))
                .isEqualTo(new TransacaoProcessadaRecebida(EVENT_ID, TRANSACTION_ID, OCCURRED_AT,
                        TRANSACTION_ID, CAUSE_ID, StatusTransacao.APROVADA));
    }

    private static String payload(StatusTransacao status) {
        return """
                {"eventId":"%s","eventType":"TransacaoProcessada","eventVersion":1,
                 "occurredAt":"%s","correlationId":"%s","causationId":"%s",
                 "data":{"transactionId":"%s","status":"%s"}}
                """.formatted(EVENT_ID, OCCURRED_AT, TRANSACTION_ID, CAUSE_ID, TRANSACTION_ID, status);
    }

    private static Message mensagem(String payload) {
        var properties = new MessageProperties();
        properties.setMessageId(EVENT_ID.toString());
        properties.setType("TransacaoProcessada");
        properties.setCorrelationId(TRANSACTION_ID.toString());
        return new Message(payload.getBytes(StandardCharsets.UTF_8), properties);
    }
}
