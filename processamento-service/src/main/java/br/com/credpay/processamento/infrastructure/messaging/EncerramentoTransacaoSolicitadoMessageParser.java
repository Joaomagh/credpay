package br.com.credpay.processamento.infrastructure.messaging;

import tools.jackson.core.JacksonException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import br.com.credpay.processamento.application.PedidoEncerramentoRecebido;
import br.com.credpay.processamento.application.PedidoEncerramentoRecebido.Motivo;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.DeserializationFeature;
import org.springframework.amqp.core.Message;

final class EncerramentoTransacaoSolicitadoMessageParser {

    private final ObjectReader reader;

    EncerramentoTransacaoSolicitadoMessageParser(ObjectMapper objectMapper) {
        this.reader = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    PedidoEncerramentoRecebido parsear(Message message) {
        JsonNode root;
        try {
            root = reader.readTree(message.getBody());
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("JSON inválido");
        }
        if (root == null || !root.isObject()) throw invalido("envelope");
        if (!"EncerramentoTransacaoSolicitado".equals(texto(root, "eventType"))) throw invalido("eventType");
        var version = root.path("eventVersion");
        if (!version.isInt() || version.intValue() != 1) throw invalido("eventVersion");
        var data = root.path("data");
        if (!data.isObject()) throw invalido("data");
        var eventId = uuid(root, "eventId");
        var transactionId = uuid(data, "transactionId");
        var creationEventId = uuid(data, "creationEventId");
        var correlationId = uuid(root, "correlationId");
        if (!correlationId.equals(transactionId)) throw invalido("correlationId");
        var causationId = uuid(root, "causationId");
        if (!causationId.equals(creationEventId)) throw invalido("causationId");
        var occurredAt = instante(root);
        if (!"FALHA_PERMANENTE_CONTROLADA".equals(texto(data, "motivo"))) throw invalido("motivo");
        var evidenceRef = uuid(data, "evidenceRef");
        var properties = message.getMessageProperties();
        if (!eventId.toString().equals(properties.getMessageId())
                || !"EncerramentoTransacaoSolicitado".equals(properties.getType())
                || !transactionId.toString().equals(properties.getCorrelationId())) {
            throw new IllegalArgumentException("propriedades AMQP inconsistentes");
        }
        return new PedidoEncerramentoRecebido(eventId, transactionId, creationEventId,
                occurredAt, correlationId, Motivo.FALHA_PERMANENTE_CONTROLADA, evidenceRef);
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
