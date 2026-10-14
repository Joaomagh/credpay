package br.com.credpay.processamento.application;

import java.time.Clock;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

public class RegistrarEncerramentoService {

    private final ProcessamentoRepository processamentos;
    private final EncerramentoRepository encerramentos;
    private final OutboxEncerramentoRepository outbox;
    private final Clock clock;
    private final GeradorEventIdSaida gerador;
    private final ObjectMapper objectMapper;

    public RegistrarEncerramentoService(ProcessamentoRepository processamentos,
            EncerramentoRepository encerramentos, OutboxEncerramentoRepository outbox,
            Clock clock, GeradorEventIdSaida gerador, ObjectMapper objectMapper) {
        this.processamentos = processamentos;
        this.encerramentos = encerramentos;
        this.outbox = outbox;
        this.clock = clock;
        this.gerador = gerador;
        this.objectMapper = objectMapper;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EncerramentoRegistrado executar(PedidoEncerramentoRecebido pedido) {
        processamentos.bloquearIdentidades(pedido.eventId(), pedido.transactionId(), pedido.creationEventId());
        var financeiro = processamentos.buscarPorEventId(pedido.creationEventId());
        if (financeiro.isEmpty() && processamentos.existePorTransactionId(pedido.transactionId())) {
            throw new ConflitoProcessamentoException("identidade do encerramento divergente");
        }
        var arbitragem = ArbitrarEncerramento.executar(pedido.transactionId(), pedido.creationEventId(), financeiro);
        var resultado = switch (arbitragem.status()) {
            case BLOQUEIO_NECESSARIO -> EncerramentoRegistrado.Resultado.CONFIRMADO;
            case DECISAO_EXISTENTE -> EncerramentoRegistrado.Resultado.DECISAO_EXISTENTE;
        };
        var registro = new EncerramentoRegistrado(pedido, resultado,
                gerador.gerar(), clock.instant(), arbitragem.outputEventId());
        encerramentos.inserir(registro);
        outbox.adicionar(criarResposta(registro));
        return registro;
    }

    private EventoSaidaPendente criarResposta(EncerramentoRegistrado registro) {
        var pedido = registro.pedido();
        var payload = new RespostaPayload(registro.responseEventId(), "EncerramentoTransacaoRespondido", 1,
                registro.respondedAt().toString(), pedido.transactionId(), pedido.eventId(),
                new RespostaDados(pedido.transactionId(), pedido.creationEventId(), registro.resultado().name(),
                        registro.outputEventId().orElse(null)));
        try {
            return new EventoSaidaPendente(registro.responseEventId(), pedido.transactionId(),
                    "EncerramentoTransacaoRespondido", 1, objectMapper.writeValueAsString(payload),
                    registro.respondedAt());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("nao foi possivel serializar resposta de encerramento", exception);
        }
    }

    private record RespostaPayload(UUID eventId, String eventType, int eventVersion, String occurredAt,
            UUID correlationId, UUID causationId, RespostaDados data) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record RespostaDados(UUID transactionId, UUID creationEventId, String resultado, UUID outputEventId) {
    }
}
