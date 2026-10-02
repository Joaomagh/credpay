package br.com.credpay.processamento.infrastructure.messaging;

import br.com.credpay.processamento.application.TransacaoCriadaRecebida;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Currency;
import java.util.UUID;
import org.springframework.amqp.core.Message;

final class TransacaoCriadaMessageParser {

    private final ObjectMapper objectMapper;

    TransacaoCriadaMessageParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    TransacaoCriadaRecebida parsear(Message message) {
        JsonNode root;
        try {
            root = objectMapper.readTree(message.getBody());
        } catch (IOException exception) {
            throw new IllegalArgumentException("JSON inválido");
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("envelope inválido");
        }
        if (!"TransacaoCriada".equals(texto(root, "eventType"))) {
            throw new IllegalArgumentException("eventType inválido");
        }
        var eventVersion = root.path("eventVersion");
        if (!eventVersion.isInt() || eventVersion.intValue() != 1) {
            throw new IllegalArgumentException("eventVersion inválido");
        }

        var data = root.path("data");
        if (!data.isObject()) {
            throw new IllegalArgumentException("data inválido");
        }
        var eventId = uuid(root, "eventId");
        var transactionId = uuid(data, "transactionId");
        var occurredAt = instante(root, "occurredAt");
        var correlationId = uuid(root, "correlationId");
        var valor = valor(data);
        var moeda = moeda(data);
        if (!"PENDENTE".equals(texto(data, "status"))) {
            throw new IllegalArgumentException("status inválido");
        }
        var propriedades = message.getMessageProperties();
        if (!eventId.toString().equals(propriedades.getMessageId())
                || !"TransacaoCriada".equals(propriedades.getType())
                || !transactionId.toString().equals(propriedades.getCorrelationId())) {
            throw new IllegalArgumentException("propriedades AMQP inconsistentes");
        }
        return new TransacaoCriadaRecebida(eventId, transactionId, occurredAt, correlationId, valor, moeda);
    }

    private String texto(JsonNode objeto, String campo) {
        var valor = objeto.path(campo);
        if (!valor.isTextual()) {
            throw new IllegalArgumentException(campo + " inválido");
        }
        return valor.textValue();
    }

    private UUID uuid(JsonNode objeto, String campo) {
        try {
            var texto = texto(objeto, campo);
            var uuid = UUID.fromString(texto);
            if (!uuid.toString().equalsIgnoreCase(texto)) {
                throw new IllegalArgumentException();
            }
            return uuid;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(campo + " inválido");
        }
    }

    private Instant instante(JsonNode objeto, String campo) {
        try {
            return Instant.parse(texto(objeto, campo));
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(campo + " inválido");
        }
    }

    private BigDecimal valor(JsonNode data) {
        try {
            return new BigDecimal(texto(data, "amount"));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("amount inválido");
        }
    }

    private Currency moeda(JsonNode data) {
        try {
            return Currency.getInstance(texto(data, "currency"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("currency inválido");
        }
    }
}
