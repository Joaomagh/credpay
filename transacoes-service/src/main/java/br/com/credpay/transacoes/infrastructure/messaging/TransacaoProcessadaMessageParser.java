package br.com.credpay.transacoes.infrastructure.messaging;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import br.com.credpay.transacoes.application.TransacaoProcessadaRecebida;
import br.com.credpay.transacoes.domain.StatusTransacao;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.amqp.core.Message;

final class TransacaoProcessadaMessageParser {

    private final ObjectReader reader;

    TransacaoProcessadaMessageParser(ObjectMapper objectMapper) {
        this.reader = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    TransacaoProcessadaRecebida parsear(Message message) {
        JsonNode root;
        try {
            root = reader.readTree(message.getBody());
        } catch (IOException exception) {
            throw new IllegalArgumentException("JSON inválido");
        }
        if (root == null || !root.isObject()) throw invalido("envelope");
        if (!"TransacaoProcessada".equals(texto(root, "eventType"))) throw invalido("eventType");
        var version = root.path("eventVersion");
        if (!version.isInt() || version.intValue() != 1) throw invalido("eventVersion");
        var data = root.path("data");
        if (!data.isObject()) throw invalido("data");
        var eventId = uuid(root, "eventId");
        var transactionId = uuid(data, "transactionId");
        var correlationId = uuid(root, "correlationId");
        if (!correlationId.equals(transactionId)) throw invalido("correlationId");
        var causationId = uuid(root, "causationId");
        var occurredAt = instante(root);
        var status = switch (texto(data, "status")) {
            case "APROVADA" -> StatusTransacao.APROVADA;
            case "REJEITADA" -> StatusTransacao.REJEITADA;
            default -> throw invalido("status");
        };
        var properties = message.getMessageProperties();
        if (!eventId.toString().equals(properties.getMessageId())
                || !"TransacaoProcessada".equals(properties.getType())
                || !transactionId.toString().equals(properties.getCorrelationId())) {
            throw new IllegalArgumentException("propriedades AMQP inconsistentes");
        }
        return new TransacaoProcessadaRecebida(eventId, transactionId, occurredAt,
                correlationId, causationId, status);
    }

    private String texto(JsonNode objeto, String campo) {
        var valor = objeto.path(campo);
        if (!valor.isTextual()) throw invalido(campo);
        return valor.textValue();
    }

    private UUID uuid(JsonNode objeto, String campo) {
        var texto = texto(objeto, campo);
        try {
            var id = UUID.fromString(texto);
            if (!id.toString().equalsIgnoreCase(texto)) throw invalido(campo);
            return id;
        } catch (IllegalArgumentException exception) {
            throw invalido(campo);
        }
    }

    private Instant instante(JsonNode root) {
        try {
            return Instant.parse(texto(root, "occurredAt"));
        } catch (DateTimeParseException exception) {
            throw invalido("occurredAt");
        }
    }

    private IllegalArgumentException invalido(String campo) {
        return new IllegalArgumentException(campo + " inválido");
    }
}
