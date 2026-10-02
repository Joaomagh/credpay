package br.com.credpay.processamento.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class TransacaoCriadaMessageParserTest {

    private static final UUID EVENT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID TRANSACTION_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private final TransacaoCriadaMessageParser parser = new TransacaoCriadaMessageParser(new ObjectMapper());

    @Test
    void parsear_deveMapearEvento_quandoEnvelopeEPropriedadesSaoValidos() {
        var mensagem = mensagem("1");

        var recebida = parser.parsear(mensagem);

        assertThat(recebida.eventId()).isEqualTo(EVENT_ID);
        assertThat(recebida.transactionId()).isEqualTo(TRANSACTION_ID);
        assertThat(recebida.occurredAt()).isEqualTo(Instant.parse("2026-10-02T12:00:00Z"));
        assertThat(recebida.correlationId()).isEqualTo(TRANSACTION_ID);
        assertThat(recebida.valor()).isEqualByComparingTo(new BigDecimal("10.25"));
        assertThat(recebida.moeda()).isEqualTo(Currency.getInstance("BRL"));
    }

    @Test
    void parsear_deveRejeitarEvento_quandoVersaoNaoEhSuportada() {
        assertThatThrownBy(() -> parser.parsear(mensagem("2")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventVersion");
    }

    @Test
    void parsear_deveRejeitarEvento_quandoVersaoEhTexto() {
        assertThatThrownBy(() -> parser.parsear(mensagem("\"1\"")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventVersion");
    }

    @Test
    void parsear_deveRejeitarEvento_quandoValorNaoEhTextoDecimal() {
        var mensagem = mensagemAlterada("\"amount\":\"10.25\"", "\"amount\":10.25");

        assertThatThrownBy(() -> parser.parsear(mensagem))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount");
    }

    @Test
    void parsear_deveRejeitarEvento_quandoMessageIdDiverge() {
        var mensagem = mensagem("1");
        mensagem.getMessageProperties().setMessageId(UUID.randomUUID().toString());

        assertThatThrownBy(() -> parser.parsear(mensagem))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AMQP");
    }

    @Test
    void parsear_deveRejeitarEvento_quandoTypeAmqpDiverge() {
        var mensagem = mensagem("1");
        mensagem.getMessageProperties().setType("Outro");

        assertThatThrownBy(() -> parser.parsear(mensagem))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AMQP");
    }

    @Test
    void parsear_deveRejeitarEvento_quandoCorrelationIdAmqpDiverge() {
        var mensagem = mensagem("1");
        mensagem.getMessageProperties().setCorrelationId(UUID.randomUUID().toString());

        assertThatThrownBy(() -> parser.parsear(mensagem))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("AMQP");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("camposInvalidos")
    void parsear_deveRejeitarEvento_quandoCampoObrigatorioEhInvalido(
            String caso, String original, String substituto) {
        var mensagem = mensagemAlterada(original, substituto);

        assertThatThrownBy(() -> parser.parsear(mensagem))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Stream<Arguments> camposInvalidos() {
        return Stream.of(
                Arguments.of("tipo", "\"eventType\":\"TransacaoCriada\"", "\"eventType\":\"Outro\""),
                Arguments.of("status", "\"status\":\"PENDENTE\"", "\"status\":\"APROVADA\""),
                Arguments.of("valor zero", "\"amount\":\"10.25\"", "\"amount\":\"0\""),
                Arguments.of("valor negativo", "\"amount\":\"10.25\"", "\"amount\":\"-1\""),
                Arguments.of("moeda", "\"currency\":\"BRL\"", "\"currency\":\"ZZZ\""),
                Arguments.of("instante", "2026-10-02T12:00:00Z", "sem-instante"),
                Arguments.of("correlação JSON", "\"correlationId\":\"" + TRANSACTION_ID + "\"",
                        "\"correlationId\":\"" + EVENT_ID + "\""),
                Arguments.of("dados ausentes", "\"data\":{", "\"outro\":{")
        );
    }

    @Test
    void parsear_deveRejeitarEvento_semExporPayloadNaMensagemDeErro() {
        var mensagem = mensagemAlterada(EVENT_ID.toString(), "segredo-no-payload");

        assertThatThrownBy(() -> parser.parsear(mensagem))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("segredo-no-payload");
    }

    @Test
    void parsear_deveRejeitarEvento_quandoUuidNaoEhCanonico() {
        var mensagem = mensagemAlterada(EVENT_ID.toString(), "1-1-1-1-1");
        mensagem.getMessageProperties().setMessageId(UUID.fromString("1-1-1-1-1").toString());

        assertThatThrownBy(() -> parser.parsear(mensagem))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId");
    }

    @Test
    void parsear_deveAceitarEvento_quandoHaCampoAdicionalCompativel() {
        var mensagem = mensagemAlterada("\"status\":\"PENDENTE\"",
                "\"status\":\"PENDENTE\",\"extra\":\"ignorado\"");

        assertThat(parser.parsear(mensagem).eventId()).isEqualTo(EVENT_ID);
    }

    @Test
    void parsear_deveRejeitarEvento_quandoJsonNaoEhObjeto() {
        var base = mensagem("1");
        var mensagem = new Message("[]".getBytes(StandardCharsets.UTF_8), base.getMessageProperties());

        assertThatThrownBy(() -> parser.parsear(mensagem))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsear_deveRejeitarEvento_quandoJsonEhMalformado() {
        var base = mensagem("1");
        var mensagem = new Message("{".getBytes(StandardCharsets.UTF_8), base.getMessageProperties());

        assertThatThrownBy(() -> parser.parsear(mensagem))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON");
    }

    private Message mensagemAlterada(String original, String substituto) {
        var mensagem = mensagem("1");
        var json = new String(mensagem.getBody(), StandardCharsets.UTF_8).replace(original, substituto);
        return new Message(json.getBytes(StandardCharsets.UTF_8), mensagem.getMessageProperties());
    }

    private Message mensagem(String versao) {
        var json = """
                {"eventId":"%s","eventType":"TransacaoCriada","eventVersion":%s,
                 "occurredAt":"2026-10-02T12:00:00Z","correlationId":"%s",
                 "data":{"transactionId":"%s","amount":"10.25","currency":"BRL","status":"PENDENTE"}}
                """.formatted(EVENT_ID, versao, TRANSACTION_ID, TRANSACTION_ID);
        var propriedades = new MessageProperties();
        propriedades.setMessageId(EVENT_ID.toString());
        propriedades.setType("TransacaoCriada");
        propriedades.setCorrelationId(TRANSACTION_ID.toString());
        return new Message(json.getBytes(StandardCharsets.UTF_8), propriedades);
    }
}
