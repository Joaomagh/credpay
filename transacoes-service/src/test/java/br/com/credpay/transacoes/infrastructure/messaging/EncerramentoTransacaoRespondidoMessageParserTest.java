package br.com.credpay.transacoes.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import br.com.credpay.transacoes.application.RespostaEncerramentoRecebida;
import br.com.credpay.transacoes.application.RespostaEncerramentoRecebida.Resultado;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class EncerramentoTransacaoRespondidoMessageParserTest {

    private static final UUID EVENT_ID = UUID.randomUUID();
    private static final UUID TRANSACTION_ID = UUID.randomUUID();
    private static final UUID CREATION_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID OUTPUT_ID = UUID.randomUUID();
    private static final Instant OCCURRED_AT = Instant.parse("2026-10-15T12:00:00.123456789Z");
    private final EncerramentoTransacaoRespondidoMessageParser parser =
            new EncerramentoTransacaoRespondidoMessageParser(new ObjectMapper());

    @Test
    void parsear_devePreservarEnvelopeCausaDoPedidoENanos_quandoConfirmado() {
        assertThat(parser.parsear(mensagem(payload())))
                .isEqualTo(new RespostaEncerramentoRecebida(EVENT_ID, TRANSACTION_ID, CREATION_ID,
                        OCCURRED_AT, TRANSACTION_ID, REQUEST_ID, Resultado.CONFIRMADO, Optional.empty()));
    }

    @Test
    void parsear_devePreservarSaidaFinanceira_quandoDecisaoExistente() throws Exception {
        var root = (ObjectNode) new ObjectMapper().readTree(payload());
        var data = (ObjectNode) root.get("data");
        data.put("resultado", "DECISAO_EXISTENTE");
        data.put("outputEventId", OUTPUT_ID.toString());

        assertThat(parser.parsear(mensagem(root.toString())))
                .isEqualTo(new RespostaEncerramentoRecebida(EVENT_ID, TRANSACTION_ID, CREATION_ID,
                        OCCURRED_AT, TRANSACTION_ID, REQUEST_ID, Resultado.DECISAO_EXISTENTE,
                        Optional.of(OUTPUT_ID)));
    }

    @ParameterizedTest
    @MethodSource("camposInvalidos")
    void parsear_deveRecusarCampoInvalidoSemExporConteudo_quandoContratoInvalido(
            String campo, String valor) throws Exception {
        var mapper = new ObjectMapper();
        var root = (ObjectNode) mapper.readTree(payload());
        var data = (ObjectNode) root.get("data");
        var objeto = switch (campo) {
            case "transactionId", "creationEventId", "resultado", "outputEventId" -> data;
            default -> root;
        };
        if (campo.equals("outputEventId")) data.put("resultado", "DECISAO_EXISTENTE");
        if (valor.equals("ausente")) objeto.remove(campo);
        else objeto.set(campo, mapper.readTree(valor));

        assertThatThrownBy(() -> parser.parsear(mensagem(root.toString())))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(campo + " inválido").hasNoCause();
    }

    static Stream<Arguments> camposInvalidos() {
        var tipos = Stream.of("eventType", "eventVersion", "eventId", "correlationId", "causationId",
                        "occurredAt", "data", "transactionId", "creationEventId", "resultado", "outputEventId")
                .flatMap(campo -> Stream.of("ausente", "null", "true", "12", "{}", "[]")
                        .filter(valor -> !campo.equals("data") || !valor.equals("{}"))
                        .map(valor -> Arguments.of(campo, valor)));
        var ids = Stream.of("eventId", "correlationId", "causationId", "transactionId",
                        "creationEventId", "outputEventId")
                .flatMap(campo -> Stream.of("\"1-1-1-1-1\"", "\"conteudo-privado\"", "\"\"",
                                "\" " + CREATION_ID + "\"")
                        .map(valor -> Arguments.of(campo, valor)));
        var literais = Stream.of(
                Arguments.of("eventType", "\"TransacaoProcessada\""),
                Arguments.of("eventVersion", "2"), Arguments.of("eventVersion", "1.0"),
                Arguments.of("eventVersion", "\"1\""), Arguments.of("eventVersion", "2147483648"),
                Arguments.of("correlationId", "\"" + CREATION_ID + "\""),
                Arguments.of("occurredAt", "\"instante-privado\""),
                Arguments.of("resultado", "\"FALHOU\""), Arguments.of("resultado", "\"confirmado\""));
        return Stream.concat(Stream.concat(tipos, ids), literais);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "true", "12", "{}", "[]", "\"conteudo-privado\""})
    void parsear_deveRecusarOutputMesmoNulo_quandoConfirmado(String valor) throws Exception {
        var mapper = new ObjectMapper();
        var root = (ObjectNode) mapper.readTree(payload());
        ((ObjectNode) root.get("data")).set("outputEventId", mapper.readTree(valor));

        assertThatThrownBy(() -> parser.parsear(mensagem(root.toString())))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("outputEventId inválido").hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "[]", "12", "true", "\"texto\"", "{\"privado\":"})
    void parsear_deveRecusarJsonOuEnvelopeSemExporConteudo_quandoCorpoInvalido(String json) {
        assertThatThrownBy(() -> parser.parsear(mensagem(json)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(json.startsWith("{") ? "JSON inválido" : "envelope inválido").hasNoCause();
    }

    @ParameterizedTest
    @ValueSource(strings = {" {}", " conteudo-privado"})
    void parsear_deveRecusarConteudoAposEnvelope_quandoCorpoPossuiSufixo(String sufixo) {
        assertThatThrownBy(() -> parser.parsear(mensagem(payload() + sufixo)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("JSON inválido").hasNoCause();
    }

    @ParameterizedTest
    @MethodSource("propriedadesInvalidas")
    void parsear_deveRecusarPropriedadeSemExporConteudo_quandoAmqpDiverge(String campo, String valor) {
        var message = mensagem(payload());
        switch (campo) {
            case "messageId" -> message.getMessageProperties().setMessageId(valor);
            case "type" -> message.getMessageProperties().setType(valor);
            case "correlationId" -> message.getMessageProperties().setCorrelationId(valor);
            default -> throw new AssertionError("campo inválido na fixture");
        }
        assertThatThrownBy(() -> parser.parsear(message))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("propriedades AMQP inconsistentes").hasNoCause();
    }

    static Stream<Arguments> propriedadesInvalidas() {
        return Stream.of("messageId", "type", "correlationId")
                .flatMap(campo -> Stream.of(null, "conteudo-privado")
                        .map(valor -> Arguments.of(campo, valor)));
    }

    @Test
    void parsear_deveRecusarSaidaFinanceira_quandoConfirmado() throws Exception {
        var root = (ObjectNode) new ObjectMapper().readTree(payload());
        ((ObjectNode) root.get("data")).put("outputEventId", OUTPUT_ID.toString());

        assertThatThrownBy(() -> parser.parsear(mensagem(root.toString())))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("outputEventId inválido").hasNoCause();
    }

    @ParameterizedTest
    @EnumSource(Resultado.class)
    void parsear_deveAceitarExtrasUuidMaiusculoEOffset_quandoRepresentacaoEquivalente(Resultado resultado)
            throws Exception {
        var root = (ObjectNode) new ObjectMapper().readTree(payload());
        root.put("eventId", EVENT_ID.toString().toUpperCase(java.util.Locale.ROOT));
        root.put("correlationId", TRANSACTION_ID.toString().toUpperCase(java.util.Locale.ROOT));
        root.put("causationId", REQUEST_ID.toString().toUpperCase(java.util.Locale.ROOT));
        root.put("occurredAt", "2026-10-15T09:00:00.123456789-03:00");
        root.put("extensao", "compatível");
        var data = (ObjectNode) root.get("data");
        data.put("transactionId", TRANSACTION_ID.toString().toUpperCase(java.util.Locale.ROOT));
        data.put("creationEventId", CREATION_ID.toString().toUpperCase(java.util.Locale.ROOT));
        data.put("resultado", resultado.name());
        data.put("extensao", 42);
        var output = resultado == Resultado.DECISAO_EXISTENTE ? Optional.of(OUTPUT_ID) : Optional.<UUID>empty();
        output.ifPresent(id -> data.put("outputEventId", id.toString().toUpperCase(java.util.Locale.ROOT)));

        assertThat(parser.parsear(mensagem(root.toString())))
                .isEqualTo(new RespostaEncerramentoRecebida(EVENT_ID, TRANSACTION_ID, CREATION_ID,
                        OCCURRED_AT, TRANSACTION_ID, REQUEST_ID, resultado, output));
    }

    @Test
    void parsear_devePreservarConfiguracaoCompartilhada_quandoRejeitaSufixo() throws Exception {
        var mapper = tools.jackson.databind.json.JsonMapper.builder()
                .disable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
        var parserLocal = new EncerramentoTransacaoRespondidoMessageParser(mapper);
        var json = payload() + " {}";

        assertThatThrownBy(() -> parserLocal.parsear(mensagem(json)))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("JSON inválido").hasNoCause();
        assertThat(mapper.readTree(json).path("eventType").textValue()).isEqualTo("EncerramentoTransacaoRespondido");
    }

    private static String payload() {
        return """
                {"eventId":"%s","eventType":"EncerramentoTransacaoRespondido","eventVersion":1,
                 "occurredAt":"%s","correlationId":"%s","causationId":"%s",
                 "data":{"transactionId":"%s","creationEventId":"%s","resultado":"CONFIRMADO"}}
                """.formatted(EVENT_ID, OCCURRED_AT, TRANSACTION_ID, REQUEST_ID, TRANSACTION_ID, CREATION_ID);
    }

    private static Message mensagem(String payload) {
        var properties = new MessageProperties();
        properties.setMessageId(EVENT_ID.toString());
        properties.setType("EncerramentoTransacaoRespondido");
        properties.setCorrelationId(TRANSACTION_ID.toString());
        return new Message(payload.getBytes(StandardCharsets.UTF_8), properties);
    }
}
